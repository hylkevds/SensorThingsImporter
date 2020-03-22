/*
 * Copyright (C) 2020 Fraunhofer IOSB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.sensorthingsimporter.importers;

import com.google.gson.JsonElement;
import de.fraunhofer.iosb.ilt.configurable.AnnotatedConfigurable;
import de.fraunhofer.iosb.ilt.configurable.ConfigEditor;
import de.fraunhofer.iosb.ilt.configurable.ConfigurationException;
import de.fraunhofer.iosb.ilt.configurable.annotations.ConfigurableField;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorBoolean;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorString;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorSubclass;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.ImportException;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.Importer;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.timegen.TimeGen;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.utils.FrostUtils;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.utils.UrlUtils;
import de.fraunhofer.iosb.ilt.sta.ServiceFailureException;
import de.fraunhofer.iosb.ilt.sta.Utils;
import de.fraunhofer.iosb.ilt.sta.dao.BaseDao;
import de.fraunhofer.iosb.ilt.sta.model.Datastream;
import de.fraunhofer.iosb.ilt.sta.model.Entity;
import de.fraunhofer.iosb.ilt.sta.model.FeatureOfInterest;
import de.fraunhofer.iosb.ilt.sta.model.Location;
import de.fraunhofer.iosb.ilt.sta.model.Observation;
import de.fraunhofer.iosb.ilt.sta.model.ObservedProperty;
import de.fraunhofer.iosb.ilt.sta.model.Sensor;
import de.fraunhofer.iosb.ilt.sta.model.Thing;
import de.fraunhofer.iosb.ilt.sta.model.ext.EntityList;
import de.fraunhofer.iosb.ilt.sta.model.ext.UnitOfMeasurement;
import de.fraunhofer.iosb.ilt.sta.query.Query;
import de.fraunhofer.iosb.ilt.sta.service.SensorThingsService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.geojson.Point;
import org.geotools.geometry.DirectPosition2D;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Interval;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 *
 * @author hylke
 */
public class ImporterAtAqd implements Importer, AnnotatedConfigurable<SensorThingsService, Object> {

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ImporterAtAqd.class.getName());

	private static final String TAG_OWNER = "owner";
	private static final String TAG_METADATA = "metadata";
	private static final String TAG_END_TIME = "endTime";
	private static final String TAG_BEGIN_TIME = "beginTime";
	private static final String TAG_MOBILE = "mobile";
	private static final String TAG_MEASUREMENT_REGIME = "measurementRegime";
	private static final String TAG_MEDIA_MONITORED = "mediaMonitored";
	private static final String TAG_NAMESPACE = "namespace";
	private static final String TAG_LOCAL_ID = "localId";
	private static final String TAG_RECOMMENDED_UNIT = "recommendedUnit";

	private static final Pattern SENSOR_ID_PATTERN = Pattern.compile("^(SPP\\.[0-9]+\\.[0-9A-Za-z]+\\.[0-9]+\\.([0-9]+))\\.([0-9]+)\\.([0-9]+)$");

	@ConfigurableField(editor = EditorBoolean.class,
			label = "Full Import",
			description = "Import not just Observations, but also Things, Locations, etc.")
	@EditorBoolean.EdOptsBool()
	private boolean fullImport;

	@ConfigurableField(editor = EditorString.class,
			label = "StationUrl",
			description = "The url to use to fetch Station+Location features.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_Station")
	private String thingsUrl;

	@ConfigurableField(editor = EditorString.class,
			label = "ProcessUrl",
			description = "The url to use to fetch SamplingPointProcess features.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_SamplingPointProcess")
	private String sensorsUrl;

	@ConfigurableField(editor = EditorString.class,
			label = "SamplesUrl",
			description = "The url to use to fetch Sample features.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_Sample")
	private String samplesUrl;

	@ConfigurableField(editor = EditorString.class,
			label = "SamplingPointsUrl",
			description = "The url to use to fetch Sampling Point features.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_SamplingPoint")
	private String samplingPointsUrl;

	@ConfigurableField(editor = EditorString.class,
			label = "ObservationsUrl",
			description = "The url to use to fetch Observations, with placeholders for {datastreamLocalId} and {phenomenonTimeInterval}.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at/inspire/sos?service=SOS&version=2.0.0&request=getObservation&offering=urn:STA/{datastreamLocalId}&eventTime={phenomenonTimeInterval}")
	private String observationsUrl;

	@ConfigurableField(editor = EditorString.class,
			label = "EntityOwner",
			description = "The string to use for the 'owner' field in entities.")
	@EditorString.EdOptsString(dflt = "http://luft.umweltbundesamt.at")
	private String entityOwner;

	@ConfigurableField(editor = EditorString.class,
			label = "Start Time Field",
			description = "The field holding the start time of the phenomenonTime interval.")
	@EditorString.EdOptsString(dflt = "StartTime")
	private String fieldStartTime;

	@ConfigurableField(editor = EditorString.class,
			label = "End Time Field",
			description = "The field holding the end time of the phenomenonTime interval.")
	@EditorString.EdOptsString(dflt = "EndTime")
	private String fieldEndTime;

	@ConfigurableField(editor = EditorString.class,
			label = "Value Field",
			description = "The field holding the Observation value.")
	@EditorString.EdOptsString(dflt = "Value")
	private String fieldValue;

	@ConfigurableField(editor = EditorSubclass.class,
			label = "StartTime", description = "Import Observations starting at this time.")
	@EditorSubclass.EdOptsSubclass(iface = TimeGen.class)
	private TimeGen startTime;

	private boolean verbose = false;
	private boolean noAct = false;

	private SensorThingsService service;
	private FrostUtils frostUtils;

	private final NameSpaceContextMap nameSpaceContext = new NameSpaceContextMap();

	private final EntityCache<String, Location> locationsCache = new EntityCache<>();
	private final EntityCache<String, Thing> thingsCache = new EntityCache<>();
	private final EntityCache<Integer, ObservedProperty> observedPropertyCache = new EntityCache<>();
	private final EntityCache<String, Sensor> sensorCache = new EntityCache<>();
	private final EntityCache<String, FeatureOfInterest> foiCache = new EntityCache<>();
	private final EntityCache<String, Datastream> datastreamCache = new EntityCache<>();

	public ImporterAtAqd() {
		nameSpaceContext.register("ad", "urn:x-inspire:specification:gmlas:Addresses:3.0");
		nameSpaceContext.register("am", "http://inspire.ec.europa.eu/schemas/am/3.0");
		nameSpaceContext.register("aqd", "http://dd.eionet.europa.eu/schemaset/id2011850eu-1.0");
		nameSpaceContext.register("au", "urn:x-inspire:specification:gmlas:AdministrativeUnits:3.0");
		nameSpaceContext.register("base", "http://inspire.ec.europa.eu/schemas/base/3.3");
		nameSpaceContext.register("base2", "http://inspire.ec.europa.eu/schemas/base2/1.0");
		nameSpaceContext.register("ef", "http://inspire.ec.europa.eu/schemas/ef/3.0");
		nameSpaceContext.register("fes", "http://www.opengis.net/fes/2.0");
		nameSpaceContext.register("gmd", "http://www.isotc211.org/2005/gmd");
		nameSpaceContext.register("gml", "http://www.opengis.net/gml/3.2");
		nameSpaceContext.register("gn", "urn:x-inspire:specification:gmlas:GeographicalNames:3.0");
		nameSpaceContext.register("om", "http://www.opengis.net/om/2.0");
		nameSpaceContext.register("ompr", "http://inspire.ec.europa.eu/schemas/ompr/2.0");
		nameSpaceContext.register("ows", "http://www.opengis.net/ows/1.1");
		nameSpaceContext.register("sams", "http://www.opengis.net/samplingSpatial/2.0");
		nameSpaceContext.register("sos", "http://www.opengis.net/sos/2.0");
		nameSpaceContext.register("swe", "http://www.opengis.net/swe/2.0");
		nameSpaceContext.register("wfs", "http://www.opengis.net/wfs/2.0");
		nameSpaceContext.register("xlink", "http://www.w3.org/1999/xlink");
		nameSpaceContext.register("xml", "http://www.w3.org/XML/1998/namespace");
	}

	@Override
	public void configure(JsonElement config, SensorThingsService context, Object edtCtx, ConfigEditor<?> configEditor) throws ConfigurationException {
		this.service = context;
		AnnotatedConfigurable.super.configure(config, context, edtCtx, configEditor);
		frostUtils = new FrostUtils(service);
	}

	@Override
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	@Override
	public void setNoAct(boolean noAct) {
		this.noAct = noAct;
		frostUtils.setDryRun(noAct);
	}

	@Override
	public Iterator<List<Observation>> iterator() {
		try {
			loadCache();
			if (fullImport) {
				importThings();
				importObservedProperties();
				importSensors();
				importFeaturesOfInterest();
				importDatastreams();
			}
			return new ObservationListIter(foiCache, datastreamCache, observationsUrl, startTime);
		} catch (ImportException | ServiceFailureException ex) {
			throw new IllegalStateException("Failed to import.", ex);
		}
	}

	private void loadCache() throws ServiceFailureException {
		LOGGER.info("Caching entities");

		String filter = "properties/" + TAG_OWNER + " eq " + FrostUtils.quoteForUrl(entityOwner);
		final int locationCount = locationsCache.load(
				service.locations(),
				filter,
				"id,name,description,properties,encodingType,location",
				"",
				(entity) -> {
					return Objects.toString(entity.getProperties().get(TAG_LOCAL_ID).toString(), null);
				});
		LOGGER.info("Loaded {} Locations", locationCount);

		final int thingCount = thingsCache.load(
				service.things(),
				filter,
				"id,name,description,properties",
				"Locations($select=id)",
				(entity) -> {
					return Objects.toString(entity.getProperties().get(TAG_LOCAL_ID).toString(), null);
				});
		LOGGER.info("Loaded {} Things", thingCount);

		final int observedPropertyCount = observedPropertyCache.load(
				service.observedProperties(),
				filter,
				"id,name,description,definition,properties",
				"",
				(entity) -> {
					return Integer.parseInt(entity.getProperties().get(TAG_LOCAL_ID).toString());
				});
		LOGGER.info("Loaded {} ObservedProperties", observedPropertyCount);

		final int sensorCount = sensorCache.load(
				service.sensors(),
				filter,
				"id,name,description,encodingtype,metadata,properties",
				"",
				(entity) -> {
					return Objects.toString(entity.getProperties().get(TAG_LOCAL_ID).toString(), null);
				});
		LOGGER.info("Loaded {} Sensors", sensorCount);

		final int foiCount = foiCache.load(
				service.featuresOfInterest(),
				filter,
				"id,name,description,encodingtype,feature,properties",
				"",
				(entity) -> {
					return Objects.toString(entity.getProperties().get(TAG_LOCAL_ID).toString(), null);
				});
		LOGGER.info("Loaded {} FeaturesOfInterest", foiCount);

		final int datastreamCount = datastreamCache.load(
				service.datastreams(),
				filter,
				"id,name,description,unitOfMeasurement,observationType,properties,phenomenonTime",
				"",
				(entity) -> {
					return Objects.toString(entity.getProperties().get(TAG_LOCAL_ID).toString(), null);
				});
		LOGGER.info("Loaded {} FeaturesOfInterest", datastreamCount);
	}

	private void importThings() throws ImportException {
		LOGGER.info("Fetching Stations from {}", thingsUrl);
		String stationFeatureXml = UrlUtils.fetchFromUrl(thingsUrl);
		LOGGER.info("Fetched {} characters.", stationFeatureXml.length());

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(IOUtils.toInputStream(stationFeatureXml, Charset.forName("UTF-8")));

			XPathFactory xpathfactory = XPathFactory.newInstance();
			XPath xpath = xpathfactory.newXPath();
			xpath.setNamespaceContext(nameSpaceContext);

			XPathExpression exprStationsList = xpath.compile("/wfs:FeatureCollection/wfs:member/aqd:AQD_Station[./ef:operationalActivityPeriod/ef:OperationalActivityPeriod/ef:activityTime/gml:TimePeriod/gml:endPosition[@indeterminatePosition='unknown']]");
			XPathExpression exprStationId = xpath.compile("@gml:id");
			XPathExpression exprStationNameSpace = xpath.compile("ef:inspireId/base:Identifier/base:namespace");
			XPathExpression exprStationName = xpath.compile("ef:name");
			XPathExpression exprStationBeginTime = xpath.compile("ef:operationalActivityPeriod/ef:OperationalActivityPeriod/ef:activityTime/gml:TimePeriod/gml:beginPosition");
			XPathExpression exprStationEndTime = xpath.compile("ef:operationalActivityPeriod/ef:OperationalActivityPeriod/ef:activityTime/gml:TimePeriod/gml:endPosition");
			String stationMediaMonitored = "http://inspire.ec.europa.eu/codelist/MediaValue/air";
			String stationMeasurementRegime = "http://inspire.ec.europa.eu/codelist/MeasurementRegimeValue/continuousDataCollection";
			String stationMetaData = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_Station";
			boolean stationMobile = false;

			XPathExpression exprLocationSrsName = xpath.compile("ef:geometry/gml:Point/@srsName");
			XPathExpression exprLocationSrsDim = xpath.compile("ef:geometry/gml:Point/@srsDimension");
			XPathExpression exprLocationPos = xpath.compile("ef:geometry/gml:Point/gml:pos");

			NodeList stationList = (NodeList) exprStationsList.evaluate(doc, XPathConstants.NODESET);
			int count = stationList.getLength();
			LOGGER.info("Found {} stations.", count);

			for (int i = 0; i < count; i++) {
				Node stationNode = stationList.item(i);
				stationNode.getParentNode().removeChild(stationNode);

				String stationId = exprStationId.evaluate(stationNode);
				String stationName = exprStationName.evaluate(stationNode);
				String stationNamesSpace = exprStationNameSpace.evaluate(stationNode);
				String stationBeginTime = exprStationBeginTime.evaluate(stationNode);
				String stationEndTime = exprStationEndTime.evaluate(stationNode);
				String stationDescription = "Air quality station " + stationName;

				String locationSrsName = exprLocationSrsName.evaluate(stationNode);
				String locationSrsDim = exprLocationSrsDim.evaluate(stationNode);
				String locationPos = exprLocationPos.evaluate(stationNode);
				String locationDescription = "Location of air quality station " + stationName;

				DirectPosition2D targetPoint = FrostUtils.convertCoordinates(locationPos, locationSrsName);

				Map<String, Object> stationProps = new HashMap<>();
				stationProps.put(TAG_OWNER, entityOwner);
				stationProps.put(TAG_LOCAL_ID, stationId);
				stationProps.put(TAG_NAMESPACE, stationNamesSpace);
				stationProps.put(TAG_MEDIA_MONITORED, stationMediaMonitored);
				stationProps.put(TAG_MEASUREMENT_REGIME, stationMeasurementRegime);
				stationProps.put(TAG_MOBILE, stationMobile);
				stationProps.put(TAG_BEGIN_TIME, stationBeginTime);
				if (!stationEndTime.isEmpty()) {
					stationProps.put(TAG_END_TIME, stationEndTime);
				}
				stationProps.put(TAG_METADATA, stationMetaData);

				Map<String, Object> locationProps = new HashMap<>();
				locationProps.put(TAG_OWNER, entityOwner);
				locationProps.put(TAG_LOCAL_ID, stationId);
				locationProps.put(TAG_NAMESPACE, stationNamesSpace);
				locationProps.put(TAG_METADATA, stationMetaData);

				String filter = "properties/" + TAG_LOCAL_ID + " eq " + Utils.quoteForUrl(stationId);
				Location cachedLocation = locationsCache.get(stationId);
				Location location = frostUtils.findOrCreateLocation(filter, stationName, locationDescription, locationProps, new Point(targetPoint.x, targetPoint.y), cachedLocation);
				Thing cachedThing = thingsCache.get(stationId);
				Thing thing = frostUtils.findOrCreateThing(filter, stationName, stationDescription, stationProps, location, cachedThing);
				locationsCache.put(stationId, location);
				thingsCache.put(stationId, thing);

				LOGGER.debug("Station: {}: {}.", stationId, stationName);
			}

		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException ex) {
			throw new ImportException("XML problem.", ex);
		} catch (FactoryException | MismatchedDimensionException | TransformException ex) {
			throw new ImportException("Coordinate conversion problem.", ex);
		} catch (ServiceFailureException ex) {
			throw new ImportException("Failed to communicate with SensorThings API service.", ex);
		}
	}

	private void importObservedProperties() throws ServiceFailureException {
		AtOpRegistry atOpRegistry = new AtOpRegistry();
		atOpRegistry.register(new AtOp(1, "SO2", "SO2", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/1"));
		atOpRegistry.register(new AtOp(5, "PM10", "PM10", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/5"));
		atOpRegistry.register(new AtOp(7, "O3", "O3", "µg/m3", "http://dd.eionet.europa.eu/vocabularyconcept/aq/pollutant/7"));
		atOpRegistry.register(new AtOp(8, "NO2", "NO2", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/8"));
		atOpRegistry.register(new AtOp(9, "NOX as NO2", "NOX as NO2", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/9"));
		atOpRegistry.register(new AtOp(10, "CO", "CO", "mg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/10"));
		atOpRegistry.register(new AtOp(38, "NO", "NO", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/38"));
		atOpRegistry.register(new AtOp(71, "CO2", "CO2", "ppmv", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/71"));
		atOpRegistry.register(new AtOp(6001, "PM2.5", "PM2.5", "µg/m3", "http://dd.eionet.europa.eu/vocabulary/aq/pollutant/6001"));

		for (AtOp atop : atOpRegistry.values()) {
			Map<String, Object> properties = new HashMap<>();
			properties.put(TAG_LOCAL_ID, atop.localId);
			properties.put(TAG_OWNER, entityOwner);
			properties.put(TAG_RECOMMENDED_UNIT, atop.recommendedUnit);

			String filter = "properties/" + TAG_LOCAL_ID + " eq " + Utils.quoteForUrl(atop.localId);
			ObservedProperty cachedObservedProperty = observedPropertyCache.get(atop.localId);
			frostUtils.findOrCreateOp(filter, atop.name, atop.definition, atop.description, properties, cachedObservedProperty);
		}
	}

	private void importSensors() throws ImportException {
		int imported = 0;
		int total = 0;
		Set<String> handledProcesses = new HashSet<>();
		LOGGER.info("Fetching Processes from {}", sensorsUrl);
		String processFeatureXml = UrlUtils.fetchFromUrl(sensorsUrl);
		LOGGER.info("Fetched {} characters.", processFeatureXml.length());
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(IOUtils.toInputStream(processFeatureXml, Charset.forName("UTF-8")));

			XPathFactory xpathfactory = XPathFactory.newInstance();
			XPath xpath = xpathfactory.newXPath();
			xpath.setNamespaceContext(nameSpaceContext);

			XPathExpression exprList = xpath.compile("/wfs:FeatureCollection/wfs:member/aqd:AQD_SamplingPointProcess");
			XPathExpression exprId = xpath.compile("@gml:id");
			XPathExpression exprNameSpace = xpath.compile("ompr:inspireId/base:Identifier/base:namespace");
			XPathExpression exprMesEquip = xpath.compile("aqd:measurementEquipment/aqd:MeasurementEquipment/aqd:equipment/@xlink:href");
			XPathExpression exprSamEquip = xpath.compile("aqd:samplingEquipment/aqd:SamplingEquipment/aqd:equipment/@xlink:href");
			XPathExpression exprSamEquipOther = xpath.compile("aqd:samplingEquipment/aqd:SamplingEquipment/aqd:otherEquipment");
			XPathExpression exprMeasurementType = xpath.compile("aqd:measurementType/@xlink:href");
			XPathExpression exprMethod = xpath.compile("aqd:measurementMethod/aqd:MeasurementMethod/aqd:measurementMethod/@xlink:href");
			XPathExpression exprDescription = xpath.compile("aqd:equivalenceDemonstration/aqd:EquivalenceDemonstration/aqd:demonstrationReport");
			XPathExpression exprRpIndividualName = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:individualName/gmd:LocalisedCharacterString");
			XPathExpression exprRpOrganisationName = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:organisationName/gmd:LocalisedCharacterString");
			XPathExpression exprRpAdminUnit = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:address/ad:AddressRepresentation/ad:adminUnit/gn:GeographicalName/gn:spelling/gn:SpellingOfName/gn:text");
			XPathExpression exprRpLocatorDesignator = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:address/ad:AddressRepresentation/ad:locatorDesignator");
			XPathExpression exprRpPostCode = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:address/ad:AddressRepresentation/ad:postCode");
			XPathExpression exprRpElectronicMailAddress = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:electronicMailAddress");
			XPathExpression exprRpTelephoneVoice = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:telephoneVoice");
			XPathExpression exprRpWebsite = xpath.compile("ompr:responsibleParty/base2:RelatedParty/base2:contact/base2:Contact/base2:website");
			String processMetadata = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_SamplingPointProcess";

			NodeList processList = (NodeList) exprList.evaluate(doc, XPathConstants.NODESET);
			total = processList.getLength();
			LOGGER.info("Found {} processes.", total);

			for (int i = 0; i < total; i++) {
				Node processNode = processList.item(i);
				processNode.getParentNode().removeChild(processNode);

				String rawProcessId = exprId.evaluate(processNode);
				Matcher matcher = SENSOR_ID_PATTERN.matcher(rawProcessId);
				if (!matcher.matches()) {
					LOGGER.error("Found illigal process id: {}", rawProcessId);
					continue;
				}
				if (Integer.parseInt(matcher.group(3)) > 1) {
					LOGGER.debug("Ignoring second process: {}", rawProcessId);
					continue;
				}
				if (!observedPropertyCache.containsId(Integer.parseInt(matcher.group(2)))) {
					LOGGER.debug("Ignoring process for unneeded OP: {}", rawProcessId);
					continue;
				}
				String processId = matcher.group(1);
				if (handledProcesses.contains(processId)) {
					LOGGER.debug("Ignoring duplocate process id: {}", rawProcessId);
					continue;
				}
				handledProcesses.add(processId);
				String measurementEquipment = exprMesEquip.evaluate(processNode);
				String samplingEquipment = exprSamEquip.evaluate(processNode);
				String samplingEquipmentOther = exprSamEquipOther.evaluate(processNode);

				String processDescription;
				String processName;
				if (!measurementEquipment.isEmpty()) {
					processDescription = measurementEquipment;
					processName = measurementEquipment.substring(measurementEquipment.lastIndexOf('/') + 1);
				} else if (!samplingEquipmentOther.isEmpty()) {
					processDescription = samplingEquipment;
					processName = samplingEquipmentOther;
				} else {
					processDescription = samplingEquipment;
					processName = samplingEquipment.substring(samplingEquipment.lastIndexOf('/') + 1);
				}
				String processMeta = exprDescription.evaluate(processNode);

				Map<String, Object> properties = new HashMap<>();
				properties.put(TAG_OWNER, entityOwner);
				properties.put(TAG_LOCAL_ID, processId);
				properties.put(TAG_NAMESPACE, exprNameSpace.evaluate(processNode));
				properties.put(TAG_METADATA, processMetadata);
				properties.put("measurementtype", exprMeasurementType.evaluate(processNode));
				properties.put("method", exprMethod.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "individualName", exprRpIndividualName.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "organisationName", exprRpOrganisationName.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "adminUnit", exprRpAdminUnit.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "locatorDesignator", exprRpLocatorDesignator.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "postCode", exprRpPostCode.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "electronicMailAddress", exprRpElectronicMailAddress.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "telephoneVoice", exprRpTelephoneVoice.evaluate(processNode));
				FrostUtils.putIntoSubMap(properties, "responsibleParty", "website", exprRpWebsite.evaluate(processNode));

				String filter = "properties/" + TAG_LOCAL_ID + " eq " + Utils.quoteForUrl(processId);
				Sensor cachedSensor = sensorCache.get(processId);
				Sensor sensor = frostUtils.findOrCreateSensor(filter, processName, processDescription, "application/pdf", processMeta, properties, cachedSensor);
				sensorCache.put(processId, sensor);
				imported++;
			}
		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException ex) {
			throw new ImportException("XML problem.", ex);
		} catch (ServiceFailureException | NumberFormatException ex) {
			throw new ImportException("Failed to communicate with SensorThings API service.", ex);
		}
		LOGGER.info("Done with processes, imported {} of {}.", imported, total);
	}

	private void importFeaturesOfInterest() throws ImportException {
		LOGGER.info("Fetching Samples from {}", samplesUrl);
		String samplesFeatureXml = UrlUtils.fetchFromUrl(samplesUrl);
		LOGGER.info("Fetched {} characters.", samplesFeatureXml.length());
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(IOUtils.toInputStream(samplesFeatureXml, Charset.forName("UTF-8")));

			XPathFactory xpathfactory = XPathFactory.newInstance();
			XPath xpath = xpathfactory.newXPath();
			xpath.setNamespaceContext(nameSpaceContext);

			XPathExpression exprSamplesList = xpath.compile("/wfs:FeatureCollection/wfs:member/aqd:AQD_Sample");
			XPathExpression exprId = xpath.compile("@gml:id");
			XPathExpression exprNameSpace = xpath.compile("aqd:inspireId/base:Identifier/base:namespace");

			XPathExpression exprSrsName = xpath.compile("sams:shape/gml:Point/@srsName");
			XPathExpression exprSrsDim = xpath.compile("sams:shape/gml:Point/@srsDimension");
			XPathExpression exprPos = xpath.compile("sams:shape/gml:Point/gml:pos");
			String featureMetaData = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_Sample";

			NodeList samplesList = (NodeList) exprSamplesList.evaluate(doc, XPathConstants.NODESET);
			int count = samplesList.getLength();
			LOGGER.info("Found {} samples.", count);

			for (int i = 0; i < count; i++) {
				Node sampleNode = samplesList.item(i);
				sampleNode.getParentNode().removeChild(sampleNode);

				String sampleId = exprId.evaluate(sampleNode);
				String sampleName = sampleId;
				String sampleDescription = "Air quality sample " + sampleName;

				Map<String, Object> properties = new HashMap<>();
				properties.put(TAG_OWNER, entityOwner);
				properties.put(TAG_LOCAL_ID, sampleId);
				properties.put(TAG_NAMESPACE, exprNameSpace.evaluate(sampleNode));
				properties.put(TAG_METADATA, featureMetaData);

				String locationSrsName = exprSrsName.evaluate(sampleNode);
				String locationPos = exprPos.evaluate(sampleNode);
				DirectPosition2D targetPoint = FrostUtils.convertCoordinates(locationPos, locationSrsName);

				String filter = "properties/" + TAG_LOCAL_ID + " eq " + Utils.quoteForUrl(sampleId);
				FeatureOfInterest cachedFoi = foiCache.get(sampleId);
				Point geoJson = new Point(targetPoint.x, targetPoint.y);
				FeatureOfInterest foi = frostUtils.findOrCreateFeature(filter, sampleName, sampleDescription, geoJson, properties, cachedFoi);
				foiCache.put(sampleId, foi);
			}
		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException ex) {
			throw new ImportException("XML problem.", ex);
		} catch (FactoryException | MismatchedDimensionException | TransformException ex) {
			throw new ImportException("Coordinate conversion problem.", ex);
		} catch (ServiceFailureException | NumberFormatException ex) {
			throw new ImportException("Failed to communicate with SensorThings API service.", ex);
		}
	}

	private void importDatastreams() throws ImportException {
		int imported = 0;
		int total = 0;
		LOGGER.info("Fetching SamplingPoints from {}", samplingPointsUrl);
		String samplingPointsFeatureXml = UrlUtils.fetchFromUrl(samplingPointsUrl);
		LOGGER.info("Fetched {} characters.", samplingPointsFeatureXml.length());
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(IOUtils.toInputStream(samplingPointsFeatureXml, Charset.forName("UTF-8")));

			XPathFactory xpathfactory = XPathFactory.newInstance();
			XPath xpath = xpathfactory.newXPath();
			xpath.setNamespaceContext(nameSpaceContext);

			XPathExpression exprList = xpath.compile("/wfs:FeatureCollection/wfs:member/aqd:AQD_SamplingPoint[./ef:operationalActivityPeriod/ef:OperationalActivityPeriod/ef:activityTime/gml:TimePeriod/gml:endPosition[@indeterminatePosition='unknown']]");
			XPathExpression exprId = xpath.compile("@gml:id");
			XPathExpression exprNameSpace = xpath.compile("ef:inspireId/base:Identifier/base:namespace");

			XPathExpression exprFoiLocalId = xpath.compile("ef:observingCapability/ef:ObservingCapability/ef:featureOfInterest/@xlink:href");
			XPathExpression exprObsPropLocalId = xpath.compile("ef:observingCapability/ef:ObservingCapability/ef:observedProperty/@xlink:href");
			XPathExpression exprSensorLocalId = xpath.compile("ef:observingCapability/ef:ObservingCapability/ef:procedure/@xlink:href");
			XPathExpression exprThingLocalId = xpath.compile("ef:broader/@xlink:href");
			String processType = "http://inspire.ec.europa.eu/codeList/ProcessTypeValue/process";
			String resultNature = "http://inspire.ec.europa.eu/codeList/ResultNatureValue/primary";
			String featureMetaData = "http://luft.umweltbundesamt.at/inspire/wfs?service=WFS&version=2.0.0&request=GetFeature&typeName=aqd:AQD_SamplingPoint";

			NodeList featureList = (NodeList) exprList.evaluate(doc, XPathConstants.NODESET);
			total = featureList.getLength();
			LOGGER.info("Found {} SamplingPoints.", total);

			for (int i = 0; i < total; i++) {
				Node feature = featureList.item(i);
				feature.getParentNode().removeChild(feature);

				String dsId = exprId.evaluate(feature);
				String dsName = dsId;

				String foiLocalId = afterLastSlash(exprFoiLocalId.evaluate(feature));
				String obsPropLocalId = afterLastSlash(exprObsPropLocalId.evaluate(feature));
				String sensorLocalId = afterLastSlash(exprSensorLocalId.evaluate(feature));
				Matcher matcher = SENSOR_ID_PATTERN.matcher(sensorLocalId);
				if (!matcher.matches()) {
					LOGGER.error("Failed to match ProcessId {}", sensorLocalId);
				}
				sensorLocalId = matcher.group(1);
				String thingLocalId = afterLastSlash(exprThingLocalId.evaluate(feature));

				if (!foiCache.containsId(foiLocalId)) {
					LOGGER.error("Specified FoI ({}) not found for feature {}.", foiLocalId, dsId);
				}
				ObservedProperty observedProperty = observedPropertyCache.get(Integer.parseInt(obsPropLocalId));
				if (observedProperty == null) {
					LOGGER.debug("Skipping {}, no ObservedProperty.", dsId);
					continue;
				}
				Sensor sensor = sensorCache.get(sensorLocalId);
				if (sensor == null) {
					LOGGER.debug("Skipping {}, no Sensor.", dsId);
					continue;
				}
				Thing thing = thingsCache.get(thingLocalId);
				if (thing == null) {
					LOGGER.debug("Skipping {}, no Thing.", dsId);
					continue;
				}
				String dsDescription = observedProperty.getName() + " as " + thing.getName();

				sensor = sensor.withOnlyId();
				thing = thing.withOnlyId();
				observedProperty = observedProperty.withOnlyId();

				Map<String, Object> properties = new HashMap<>();
				properties.put(TAG_OWNER, entityOwner);
				properties.put(TAG_LOCAL_ID, dsId);
				properties.put(TAG_NAMESPACE, exprNameSpace.evaluate(feature));
				properties.put(TAG_METADATA, featureMetaData);
				properties.put("processType", processType);
				properties.put("resultNature", resultNature);
				properties.put("featureOfInterestLocalId", foiLocalId);

				String filter = "properties/" + TAG_LOCAL_ID + " eq " + Utils.quoteForUrl(dsId);
				Datastream cachedDs = datastreamCache.get(dsId);
				UnitOfMeasurement uom = FrostUtils.NULL_UNIT;
				if (cachedDs != null && !uom.equals(cachedDs.getUnitOfMeasurement())) {
					uom = cachedDs.getUnitOfMeasurement();
				}
				Datastream ds = frostUtils.findOrCreateDatastream(filter, dsName, dsDescription, properties, uom, thing, observedProperty, sensor, cachedDs);
				datastreamCache.put(dsId, ds);
				imported++;
			}
		} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException ex) {
			throw new ImportException("XML problem.", ex);
		} catch (ServiceFailureException | NumberFormatException ex) {
			throw new ImportException("Failed to communicate with SensorThings API service.", ex);
		}
		LOGGER.info("Done with SamplingPoints, imported {} of {}.", imported, total);
	}

	private String afterLastSlash(String input) {
		return input.substring(input.lastIndexOf('/') + 1);
	}

	private class ObservationListIter implements Iterator<List<Observation>> {

		private final EntityCache<String, FeatureOfInterest> foiCache;
		private final EntityCache<String, Datastream> datastreamCache;
		private final String observationsUrl;
		private final Iterator<Datastream> datastreamIterator;
		private final TimeGen startTime;

		public ObservationListIter(EntityCache<String, FeatureOfInterest> foiCache, EntityCache<String, Datastream> datastreamCache, String observationsUrl, TimeGen startTime) {
			this.foiCache = foiCache;
			this.datastreamCache = datastreamCache;
			this.observationsUrl = observationsUrl;
			this.startTime = startTime;
			datastreamIterator = datastreamCache.values().iterator();
		}

		private List<Observation> importDatastream(Datastream ds) throws ImportException, ServiceFailureException {
			List<Observation> result = new ArrayList<>();

			Instant start = startTime.getInstant(ds);
			Instant end = Instant.now();
			Interval interval = Interval.of(start.truncatedTo(ChronoUnit.MINUTES), end.truncatedTo(ChronoUnit.MINUTES));
			String dsLocalId = ds.getProperties().get(TAG_LOCAL_ID).toString();
			String finalUrl = observationsUrl.replace("{datastreamLocalId}", dsLocalId);
			finalUrl = finalUrl.replace("{phenomenonTimeInterval}", interval.toString());
			String observationsXml = UrlUtils.fetchFromUrl(finalUrl);

			try {
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				factory.setNamespaceAware(true);
				DocumentBuilder builder = factory.newDocumentBuilder();
				Document doc = builder.parse(IOUtils.toInputStream(observationsXml, Charset.forName("UTF-8")));

				XPathFactory xpathfactory = XPathFactory.newInstance();
				XPath xpath = xpathfactory.newXPath();
				xpath.setNamespaceContext(nameSpaceContext);

				XPathExpression exprFeatureId = xpath.compile("/sos:GetObservationResponse/sos:observationData/om:OM_Observation/om:featureOfInterest/@xlink:href");
				XPathExpression exprSamplingPointId = xpath.compile("/sos:GetObservationResponse/sos:observationData/om:OM_Observation/om:parameter/om:NamedValue[./om:name/@xlink:href='http://dd.eionet.europa.eu/vocabulary/aq/processparameter/SamplingPoint']/om:value/@xlink:href");
				XPathExpression exprDataArray = xpath.compile("/sos:GetObservationResponse/sos:observationData/om:OM_Observation/om:result/swe:DataArray");

				String featureId = exprFeatureId.evaluate(doc);
				String samplingPointId = exprSamplingPointId.evaluate(doc);
				if (!samplingPointId.endsWith(dsLocalId)) {
					LOGGER.error("Returned data has sampling point {}, but expected data for {}", samplingPointId, dsLocalId);
				}
				FeatureOfInterest foi = foiCache.get(afterLastSlash(featureId));
				if (foi == null) {
					LOGGER.error("Could not find foi for {}", featureId);
					return result;
				}

				Node dataArrayNode = (Node) exprDataArray.evaluate(doc, XPathConstants.NODE);
				SweValueExtractor sweValueExtractor = new SweValueExtractor(xpath, dataArrayNode);
				SweValueExtractor.Field timeStart = new SweValueExtractor.Field(fieldStartTime, false);
				SweValueExtractor.Field timeEnd = new SweValueExtractor.Field(fieldEndTime, false);
				SweValueExtractor.Field value = new SweValueExtractor.Field(fieldValue, true);
				sweValueExtractor.addRequestedField(timeStart);
				sweValueExtractor.addRequestedField(timeEnd);
				sweValueExtractor.addRequestedField(value);
				sweValueExtractor.parse();
				LOGGER.info("Parsing {} Observations", sweValueExtractor.getElementCount());

				if (FrostUtils.NULL_UNIT.equals(ds.getUnitOfMeasurement()) && sweValueExtractor.hasNext()) {
					sweValueExtractor.next();
					String name = afterLastSlash(value.uom);
					ds.setUnitOfMeasurement(new UnitOfMeasurement(name, name, value.uom));
					frostUtils.update(ds);
					sweValueExtractor.reset();
				}

				while (sweValueExtractor.hasNext()) {
					sweValueExtractor.next();
					try {
						Observation o = new Observation();
						o.setResult(new BigDecimal(value.lastValue.trim()));
						ZonedDateTime zdtStart = ZonedDateTime.parse(timeStart.lastValue.trim());
						ZonedDateTime zdtEnd = ZonedDateTime.parse(timeEnd.lastValue.trim());
						o.setPhenomenonTimeFrom(Interval.of(zdtStart.toInstant(), zdtEnd.toInstant()));
						o.setDatastream(ds);
						o.setFeatureOfInterest(foi);
						result.add(o);
					} catch (NumberFormatException ex) {
						LOGGER.error("Failed to parse number {}", value.lastValue);
						throw new ImportException("XML problem.", ex);
					} catch (DateTimeParseException ex) {
						LOGGER.error("Failed to parse date {} or {}", timeStart.lastValue, timeEnd.lastValue);
						throw new ImportException("XML problem.", ex);
					}
				}
				return result;

			} catch (ParserConfigurationException | SAXException | IOException | XPathExpressionException ex) {
				throw new ImportException("XML problem.", ex);
			}
		}

		@Override
		public boolean hasNext() {
			return datastreamIterator.hasNext();
		}

		@Override
		public List<Observation> next() {
			if (datastreamIterator.hasNext()) {
				Datastream ds = datastreamIterator.next();
				try {
					return importDatastream(ds);
				} catch (ImportException | ServiceFailureException ex) {
					LOGGER.error("Failed to import data for datastream " + ds.getName(), ex);
				}
			}
			return Collections.emptyList();
		}

	}

	private static class NameSpaceContextMap implements NamespaceContext {

		private final HashMap<String, List<String>> prefixByUri = new HashMap<>();
		private final HashMap<String, String> uriByPrefix = new HashMap<>();

		public void register(String prefix, String uri) {
			prefixByUri.computeIfAbsent(uri, (String t) -> new ArrayList<>()).add(prefix);
			uriByPrefix.put(prefix, uri);
		}

		@Override
		public String getNamespaceURI(String prefix) {
			return uriByPrefix.get(prefix);
		}

		@Override
		public String getPrefix(String namespaceURI) {
			List<String> prefixes = prefixByUri.getOrDefault(namespaceURI, Collections.emptyList());
			if (prefixes.isEmpty()) {
				return null;
			}
			return prefixes.get(0);
		}

		@Override
		public Iterator<String> getPrefixes(String namespaceURI) {
			return prefixByUri.getOrDefault(namespaceURI, Collections.emptyList()).iterator();
		}

	}

	/**
	 *
	 * @param <T> The entity type this cache caches.
	 * @param <U> The type of the localId.
	 */
	private static class EntityCache<U, T extends Entity<T>> {

		private final Map<U, T> entitiesByLocalId = new LinkedHashMap<>();

		public T get(U localId) {
			return entitiesByLocalId.get(localId);
		}

		public boolean containsId(U localId) {
			return entitiesByLocalId.containsKey(localId);
		}

		public void put(U localId, T entity) {
			entitiesByLocalId.put(localId, entity);
		}

		public int load(BaseDao<T> dao, String filter, LocalIdExtractor<U, T> localIdExtractor) throws ServiceFailureException {
			return load(dao, filter, "", "", localIdExtractor);
		}

		public int load(BaseDao<T> dao, String filter, String select, String expand, LocalIdExtractor<U, T> localIdExtractor) throws ServiceFailureException {
			Query<T> query = dao.query();
			if (!select.isEmpty()) {
				query.select(select);
			}
			if (!expand.isEmpty()) {
				query.expand(expand);
			}
			EntityList<T> entities = query.filter(filter).top(1000).list();
			Iterator<T> it = entities.fullIterator();
			int count = 0;
			while (it.hasNext()) {
				T entitiy = it.next();
				U localId = localIdExtractor.extractFrom(entitiy);
				if (localId != null) {
					entitiesByLocalId.put(localId, entitiy);
					count++;
				}
			}
			return count;
		}

		public Collection<T> values() {
			return entitiesByLocalId.values();
		}

		private static interface LocalIdExtractor<U, T extends Entity<T>> {

			public U extractFrom(T entity);
		}
	}

	private static class AtOp {

		int localId;
		String name;
		String description;
		String definition;
		String recommendedUnit;

		public AtOp(int localId, String name, String description, String recommendedUnit, String definition) {
			this.localId = localId;
			this.name = name;
			this.description = description;
			this.recommendedUnit = recommendedUnit;
			this.definition = definition;
		}
	}

	private static class AtOpRegistry {

		private final Map<Integer, AtOp> atOpById = new HashMap<>();

		public void register(AtOp atop) {
			atOpById.put(atop.localId, atop);
		}

		public Collection<AtOp> values() {
			return atOpById.values();
		}
	}

	private static class SweValueExtractor {

		public static class Field {

			String name;
			String uom;
			boolean numeric;
			int index = -1;
			String lastValue;

			public Field(String name, boolean numeric) {
				this.name = name;
				this.numeric = numeric;
			}

			/**
			 * @return The latest value, updated by a call to next.
			 */
			public String getLastValue() {
				return lastValue;
			}

		}
		private final XPath xpath;
		private final Node dataArrayNode;

		private final Map<String, Field> requestedFields = new LinkedHashMap<>();

		private final List<Field> fields = new ArrayList<>();

		private int elementCount = -1;
		private String decimalSep;
		private String blockSep;
		private String tokenSep;

		private Matcher matcher;
		private boolean hasNext = false;

		public SweValueExtractor(XPath xpath, Node dataArrayNode) {
			this.xpath = xpath;
			this.dataArrayNode = dataArrayNode;
		}

		public void addRequestedField(Field field) {
			requestedFields.put(field.name, field);
		}

		public void parse() throws XPathExpressionException {
			elementCount = -1;
			XPathExpression exprElementCount = xpath.compile("swe:elementCount/swe:Count/swe:value");
			XPathExpression exprFieldList = xpath.compile("swe:elementType[@name='Components']/swe:DataRecord/swe:field");
			XPathExpression exprEncoding = xpath.compile("swe:encoding/swe:TextEncoding");
			XPathExpression exprValues = xpath.compile("swe:values");
			XPathExpression exprFieldUom = xpath.compile(".//swe:uom/@xlink:href");
			XPathExpression exprFieldName = xpath.compile("@name");

			elementCount = Integer.parseInt(exprElementCount.evaluate(dataArrayNode));

			Node encodingNode = (Node) exprEncoding.evaluate(dataArrayNode, XPathConstants.NODE);
			decimalSep = encodingNode.getAttributes().getNamedItem("decimalSeparator").getTextContent();
			blockSep = encodingNode.getAttributes().getNamedItem("blockSeparator").getTextContent();
			tokenSep = encodingNode.getAttributes().getNamedItem("tokenSeparator").getTextContent();

			NodeList fieldList = (NodeList) exprFieldList.evaluate(dataArrayNode, XPathConstants.NODESET);

			int fieldCount = fieldList.getLength();
			for (int i = 0; i < fieldCount; i++) {
				Node fieldNode = fieldList.item(i);
				fieldNode.getParentNode().removeChild(fieldNode);

				String fieldName = exprFieldName.evaluate(fieldNode);
				Field field = requestedFields.get(fieldName);
				if (field == null) {
					field = new Field(fieldName, false);
				} else {
					field.index = i;
					field.uom = exprFieldUom.evaluate(fieldNode);
				}
				fields.add(field);
			}

			String values = exprValues.evaluate(dataArrayNode);
			String regexBlockSep = Pattern.quote(blockSep);
			Pattern pattern = Pattern.compile("([^" + regexBlockSep + "$]+)(" + regexBlockSep + "|$)");

			matcher = pattern.matcher(values);
			hasNext = matcher.find();
		}

		public void next() {
			String block = matcher.group(1);
			String[] split = StringUtils.split(block, tokenSep);
			if (split.length != fields.size()) {
				throw new IllegalArgumentException("Found " + split.length + " fields in block, expected " + fields.size());
			}
			for (int i = 0; i < split.length; i++) {
				Field field = fields.get(i);
				field.lastValue = split[i];
				if (field.numeric) {
					field.lastValue = field.lastValue.replace(decimalSep, ".");
				}
			}
			hasNext = matcher.find();
		}

		public boolean hasNext() {
			return hasNext;
		}

		public void reset() {
			matcher.reset();
			hasNext = matcher.find();
		}

		public int getElementCount() {
			return elementCount;
		}

	}
}
