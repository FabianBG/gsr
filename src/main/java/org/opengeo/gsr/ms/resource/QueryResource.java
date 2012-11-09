package org.opengeo.gsr.ms.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.NoSuchElementException;

import net.sf.json.JSONException;
import net.sf.json.JSONSerializer;
import net.sf.json.util.JSONBuilder;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geotools.data.FeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.opengeo.gsr.core.feature.FeatureEncoder;
import org.opengeo.gsr.core.geometry.GeometryEncoder;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.restlet.Context;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.resource.OutputRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Resource;
import org.restlet.resource.Variant;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;

public class QueryResource extends Resource {
    public static Variant JSON = new Variant(MediaType.APPLICATION_JAVASCRIPT);
    public QueryResource(Context context, Request request, Response response, Catalog catalog, String format) {
        super(context, request, response);
        this.catalog = catalog;
        this.format = format;
        getVariants().add(JSON);
    }
    
    private final Catalog catalog;
    private final String format;
    private static final FilterFactory2 FILTERS = CommonFactoryFinder.getFilterFactory2();
    
    @Override
    public Representation getRepresentation(Variant variant) {
        if (variant == JSON) {
            if (!"json".equals(format)) throw new IllegalArgumentException("json is the only supported format");
            String workspace = (String) getRequest().getAttributes().get("workspace");
            String layerOrTableName = (String) getRequest().getAttributes().get("layerOrTable");
            FeatureTypeInfo featureType = catalog.getFeatureTypeByName(workspace, layerOrTableName);
            if (null == featureType) {
                throw new NoSuchElementException("No known table or layer with qualified name \"" + workspace + ":" + layerOrTableName + "\"");
            }

            final String geometryProperty;
            try {
                geometryProperty = featureType.getFeatureType().getGeometryDescriptor().getName().getLocalPart();
            } catch (IOException e) {
                throw new RuntimeException("Unable to determine geometry type for query request");
            }
            
            Form form = getRequest().getResourceRef().getQueryAsForm();
            if (!(form.getNames().contains("geometryType") && form.getNames().contains("geometry"))) {
                throw new IllegalArgumentException("'geometry' and 'geometryType' parameters are mandatory");
            }
            
            String geometryTypeName = form.getFirstValue("geometryType", "GeometryPoint");
            String geometryText = form.getFirstValue("geometry");
            Filter filter = buildGeometryFilter(geometryTypeName, geometryProperty, geometryText);
            
            if (form.getNames().contains("text")) {
                throw new UnsupportedOperationException("Text filter not implemented");
            }
            
            if (form.getNames().contains("where")) {
                String whereClause = form.getFirstValue("where");
                final Filter whereFilter;
                try {
                    whereFilter = ECQL.toFilter(whereClause);
                } catch (CQLException e) {
                    throw new IllegalArgumentException("where parameter must be valid CQL", e);
                }
                filter = FILTERS.and(filter, whereFilter);
            }
            
            String returnGeometryText = form.getFirstValue("returnGeometry");
            final boolean returnGeometry;
            if (null == returnGeometryText || "true".equalsIgnoreCase(returnGeometryText)) {
                returnGeometry = true;
            } else if ("false".equalsIgnoreCase(returnGeometryText)) {
                returnGeometry = false;
            } else {
                throw new IllegalArgumentException("Unrecognized value for returnGeometry parameter: " + returnGeometryText);
            }
            
            return new JsonQueryRepresentation(featureType, filter, returnGeometry);
        }
        return super.getRepresentation(variant);
    }
    
    private static class JsonQueryRepresentation extends OutputRepresentation {
        private final FeatureTypeInfo featureType;
        private final Filter geometryFilter;
        private final boolean returnGeometry;
        
        public JsonQueryRepresentation(FeatureTypeInfo featureType, Filter geometryFilter, boolean returnGeometry) {
            super(MediaType.APPLICATION_JAVASCRIPT);
            this.featureType = featureType;
            this.geometryFilter = geometryFilter;
            this.returnGeometry = returnGeometry;
        }
        
        @Override
        public void write(OutputStream outputStream) throws IOException {
            Writer writer = new OutputStreamWriter(outputStream, "UTF-8");
            JSONBuilder json = new JSONBuilder(writer);
            FeatureSource<? extends FeatureType, ? extends Feature> source =
                    featureType.getFeatureSource(null, null);
            FeatureEncoder.featuresToJson(source.getFeatures(geometryFilter), json, returnGeometry);
            writer.flush();
            writer.close();
        }
    }
    
    private static Filter buildGeometryFilter(String geometryType, String geometryProperty, String geometryText) {
        if ("GeometryEnvelope".equals(geometryType)) {
            Envelope e = parseShortEnvelope(geometryText);
            if (e == null) {
                e = parseJsonEnvelope(geometryText);
            }
            if (e != null) {
                return FILTERS.bbox(geometryProperty, e.getMinX(), e.getMinY(), e.getMaxX(), e.getMaxY(), null);
            }
        } else if ("GeometryPoint".equals(geometryType)) {
            com.vividsolutions.jts.geom.Point p = parseShortPoint(geometryText);
            if (p == null) {
                p = parseJsonPoint(geometryText);
            }
            if (p != null) {
                return FILTERS.intersects(FILTERS.property(geometryProperty), FILTERS.literal(p));
            } // else fall through to the catch-all exception at the end
        } else {
            try {
                net.sf.json.JSON json = JSONSerializer.toJSON(geometryText);
                com.vividsolutions.jts.geom.Geometry g = GeometryEncoder.jsonToGeometry(json);
                return FILTERS.intersects(FILTERS.property(geometryProperty), FILTERS.literal(g));
            } catch (JSONException e) {
                // fall through here to the catch-all exception at the end
            }
        }
        throw new IllegalArgumentException(
                "Can't determine geometry filter from GeometryType \""
                        + geometryType + "\" and geometry \"" + geometryText
                        + "\"");
    }
    
    private static Envelope parseShortEnvelope(String text) {
        String[] parts = text.split(",");
        if (parts.length != 4)
            return null;
        double[] coords = new double[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            final double coord;
            try {
                coord = Double.valueOf(part);
            } catch (NumberFormatException e) {
                return null;
            }
            coords[i] = coord;
        }
        // Indices are non-sequential here - JTS and GeoServices disagree on the
        // order of coordinates in an envelope.
        return new Envelope(coords[0], coords[2], coords[1], coords[3]);
    }
    
    private static Envelope parseJsonEnvelope(String text) {
        net.sf.json.JSON json = JSONSerializer.toJSON(text);
        try {
            return GeometryEncoder.jsonToEnvelope(json);
        } catch (JSONException e) {
            return null;
        }
    }
    
    private static com.vividsolutions.jts.geom.Point parseShortPoint(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2)
            return null;
        double[] coords = new double[2];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            final double coord;
            try {
                coord = Double.valueOf(part);
            } catch (NumberFormatException e) {
                return null;
            }
            coords[i] = coord;
        }
        GeometryFactory factory = new com.vividsolutions.jts.geom.GeometryFactory();
        return factory.createPoint(new Coordinate(coords[0], coords[1]));
    }
    
    private static com.vividsolutions.jts.geom.Point parseJsonPoint(String text) {
        net.sf.json.JSON json = JSONSerializer.toJSON(text);
        try {
            com.vividsolutions.jts.geom.Geometry geometry = GeometryEncoder.jsonToGeometry(json);
            if (geometry instanceof com.vividsolutions.jts.geom.Point) {
                return (com.vividsolutions.jts.geom.Point) geometry;
            } else {
                return null;
            }
        } catch (JSONException e) {
            return null;
        }
    }
}