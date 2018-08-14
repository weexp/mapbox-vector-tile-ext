package org.fengsoft.jts2geojson.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import com.wdtinc.mapbox_vector_tile.adapt.jts.*;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerBuild;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerProps;
import lombok.extern.slf4j.Slf4j;
import org.beetl.sql.core.SQLManager;
import org.beetl.sql.core.SQLReady;
import org.fengsoft.jts2geojson.convert.services.GeoJsonServicesImpl;
import org.fengsoft.jts2geojson.entity.WaterLine;
import org.fengsoft.jts2geojson.tile.GlobalGeodetic;
import org.fengsoft.jts2geojson.tile.GlobalMercator;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author JerFer
 * @Date 2018/8/1---9:43
 */
@Service
@Transactional
@Slf4j
public class WaterLineServices extends GeoJsonServicesImpl<WaterLine, Integer> {
    @Autowired
    private SQLManager sqlManager;

    private GlobalGeodetic globalGeodetic;
    private GlobalMercator globalMercator;

    public String allFeatures(String srsname, String bbox) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(toFeatures(sqlManager.all(WaterLine.class)));
    }

    public byte[] listFeature(String srsname, Integer x, Integer y, Integer z) throws ParseException {
        //计算范围
        double[] bboxs = new double[4];
        if ("4326".equals(srsname.split(":")[1])) {
            globalGeodetic = new GlobalGeodetic("", 256);
            bboxs = globalGeodetic.tileLatLonBounds(x, y, z);
            geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        } else if ("3857".equals(srsname.split(":")[1])) {
            globalMercator = new GlobalMercator(256);
            bboxs = globalMercator.tileLatLonBounds(x, y, z);
            geometryFactory = new GeometryFactory(new PrecisionModel(), 3857);
        }
        String sql = "SELECT t.id,t.name,t.shape FROM water_line t " +
                " CROSS JOIN ST_MakeEnvelope(" + bboxs[1] + "," + bboxs[0] + "," + bboxs[3] + "," + bboxs[2] + "," + srsname.split(":")[1] + ") AS geom  " +
                " WHERE ST_Intersects (shape,geom)";
        SQLReady sqlReady = new SQLReady(sql);
        List<WaterLine> res = sqlManager.execute(sqlReady, WaterLine.class);

        MvtLayerParams layerParams = new MvtLayerParams(); // Default extent
        /////////////////////////////////
        VectorTile.Tile.Builder tileBuilder = VectorTile.Tile.newBuilder();
        VectorTile.Tile.Layer.Builder layerBuilder = MvtLayerBuild.newLayerBuilder("water_line", layerParams);

        IGeometryFilter acceptAllGeomFilter = geom -> true;
        List<Geometry> geometries = res.stream().map(waterLine -> {
            try {
                return wkbReader.read(WKBReader.hexToBytes(((PGobject) (waterLine.getShape())).getValue()));
            } catch (ParseException e) {
                e.printStackTrace();
                return null;
            }
        }).filter(a -> a != null).collect(Collectors.toList());

        Envelope tileEnvelope = new Envelope(bboxs[1], bboxs[3], bboxs[0], bboxs[2]);

        TileGeomResult tileGeom = JtsAdapter.createTileGeom(geometries, tileEnvelope, geometryFactory, layerParams, acceptAllGeomFilter);

        MvtLayerProps layerProps = new MvtLayerProps();

        IUserDataConverter userDataConverter = new UserDataKeyValueMapConverter();
        List<VectorTile.Tile.Feature> features = JtsAdapter.toFeatures(tileGeom.mvtGeoms, layerProps, userDataConverter);
        layerBuilder.addAllFeatures(features);
        tileBuilder.addLayers(layerBuilder.build());
        MvtLayerBuild.writeProps(layerBuilder, layerProps);
        VectorTile.Tile mvt = tileBuilder.build();

        try {
            Files.write(Paths.get("E:/Data/vectorTile/" + String.format("%d-%d-%d", z, x, y) + ".mvt"), mvt.toByteArray());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return mvt.toByteArray();
    }

    public void readMvt() throws IOException {
        GeometryFactory geomFactory = new GeometryFactory();

        JtsMvt jtsMvt1 = MvtReader.loadMvt(
                new File("path/to/your.mvt"),
                geomFactory,
                new TagKeyValueMapConverter());


        // Allow negative-area exterior rings with classifier
        // (recommended for Mapbox compatibility)
        JtsMvt jtsMvt2 = MvtReader.loadMvt(
                new File("path/to/your.mvt"),
                geomFactory,
                new TagKeyValueMapConverter(),
                MvtReader.RING_CLASSIFIER_V1);
    }

    public MvtLayerProps writeMvt(WaterLine waterLine, VectorTile.Tile.Layer.Builder layerBuilder, MvtLayerParams layerParams) throws ParseException {
        Geometry geometry = wkbReader.read(WKBReader.hexToBytes(((PGobject) (waterLine.getShape())).getValue()));

        IGeometryFilter acceptAllGeomFilter = geom -> true;

        Envelope tileEnvelope = geometry.getEnvelopeInternal();

        TileGeomResult tileGeom = JtsAdapter.createTileGeom(geometry, tileEnvelope, geometryFactory, layerParams, acceptAllGeomFilter);

        MvtLayerProps layerProps = new MvtLayerProps();

        layerProps.addKey("id");
        layerProps.addValue(waterLine.getId());

        layerProps.addKey("name");
        layerProps.addValue(waterLine.getName());

        IUserDataConverter userDataConverter = new UserDataKeyValueMapConverter();
        List<VectorTile.Tile.Feature> features = JtsAdapter.toFeatures(tileGeom.mvtGeoms, layerProps, userDataConverter);
        features.forEach(feature -> layerBuilder.addFeatures(feature));
        return layerProps;
    }
}
