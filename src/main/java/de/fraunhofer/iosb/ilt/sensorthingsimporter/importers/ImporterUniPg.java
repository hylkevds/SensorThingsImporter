/*
 * Copyright (C) 2017 Fraunhofer IOSB
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

import com.google.common.collect.AbstractIterator;
import com.google.gson.JsonElement;
import de.fraunhofer.iosb.ilt.configurable.ConfigEditor;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorBoolean;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorClass;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorInt;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorMap;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorString;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorSubclass;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.DsMapperFilter;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.ImportException;
import de.fraunhofer.iosb.ilt.sensorthingsimporter.Importer;
import de.fraunhofer.iosb.ilt.sta.ServiceFailureException;
import de.fraunhofer.iosb.ilt.sta.model.Datastream;
import de.fraunhofer.iosb.ilt.sta.model.Observation;
import de.fraunhofer.iosb.ilt.sta.model.TimeObject;
import de.fraunhofer.iosb.ilt.sta.model.ext.EntityList;
import de.fraunhofer.iosb.ilt.sta.service.SensorThingsService;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Interval;

/**
 *
 * @author scf
 */
public class ImporterUniPg implements Importer {

	/**
	 * The logger for this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ImporterUniPg.class);

	private EditorMap<Map<String, Object>> editor;
	private EditorSubclass<SensorThingsService, Object, DocumentParser> editorDocumentParser;
	private EditorString editorDataPath;
	private EditorString editorFileIdRegex;
	private EditorInt editorDuration;
	private EditorClass<Object, Object, DsMapperFilter> editorCheckDataStream;
	private EditorBoolean editorSkipLast;
	private EditorInt editorSleep;

	private SensorThingsService service;
	private DocumentParser docParser;
	private boolean verbose;
	private DsMapperFilter checkDataStream;
	private boolean skipLast;
	private long sleepTime = 0;

	@Override
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	@Override
	public void setNoAct(boolean noAct) {
		// Nothing to set.
	}

	@Override
	public void configure(JsonElement config, SensorThingsService context, Object edtCtx) {
		service = context;
		getConfigEditor(context, edtCtx).setConfig(config);
		docParser = editorDocumentParser.getValue();
		checkDataStream = editorCheckDataStream.getValue();
		skipLast = editorSkipLast.getValue();
		sleepTime = 1000 * editorSleep.getValue();
	}

	@Override
	public ConfigEditor<?> getConfigEditor(SensorThingsService context, Object edtCtx) {
		if (editor == null) {
			editor = new EditorMap<>();

			editorDocumentParser = new EditorSubclass<>(context, edtCtx, DocumentParser.class, "DocumentParser", "The parser that transforms a document into Observations.");
			editor.addOption("documentParser", editorDocumentParser, false);

			editorFileIdRegex = new EditorString("", 1, "FileIdRegex", "A regular expression extracting an ID from the filename. Must contain 1");
			editor.addOption("fileIdRegex", editorFileIdRegex, false);

			editorDataPath = new EditorString("./importFiles", 1, "dataPath", "The path to find the data files, must be a directory.");
			editor.addOption("dataPath", editorDataPath, false);

			editorDuration = new EditorInt(0, 99999, 1, 30 * 60, "PhenomenonTimeDuration", "Seconds between start and end phenomenonTime, in seconds.");
			editor.addOption("duration", editorDuration, true);

			editorCheckDataStream = new EditorClass<>(context, edtCtx, DsMapperFilter.class, "Check Datastream", "The Datastream to check the observations of, if the file is already imported.");
			editor.addOption("checkDs", editorCheckDataStream, true);

			editorSkipLast = new EditorBoolean(false, "Skip Last File", "Skip the last file?");
			editor.addOption("skipLast", editorSkipLast, true);

			editorSleep = new EditorInt(0, 99999, 1, 0, "Sleep", "Sleep this many seconds after every imported file.");
			editor.addOption("sleep", editorSleep, true);
		}
		return editor;
	}

	@Override
	public Iterator<List<Observation>> iterator() {
		try {
			ObsListIter obsListIter = new ObsListIter();
			return obsListIter;
		} catch (ImportException exc) {
			LOGGER.error("Failed", exc);
			throw new IllegalStateException("Failed to handle csv file.", exc);
		}
	}

	private boolean alreadyImported(long fileId) throws ImportException {
		if (checkDataStream == null) {
			return false;
		}
		try {
			Datastream ds = checkDataStream.getDatastreamFor(null);
			EntityList<Observation> list = ds.observations().query().filter("parameters/importFileId eq " + fileId).select("@iot.id").list();
			return list.size() > 0;
		} catch (ServiceFailureException exc) {
			throw new ImportException(exc);
		}
	}

	private class ObsListIter extends AbstractIterator<List<Observation>> {

		Iterator<Map.Entry<Long, File>> filesIterator;
		Instant previousEndTime;

		public ObsListIter() throws ImportException {
			Map<Long, File> filesMap = fetchFiles();
			filesIterator = filesMap.entrySet().iterator();
		}

		private Map<Long, File> fetchFiles() throws ImportException {
			String targetPath = editorDataPath.getValue();
			File filesPath = new File(targetPath);
			LOGGER.info("Loading files from: {}", filesPath.getAbsolutePath());
			if (!filesPath.isDirectory()) {
				throw new ImportException("Path must be a directory.");
			}
			File[] dataFiles = filesPath.listFiles();

			Pattern idPattern = Pattern.compile(editorFileIdRegex.getValue());
			TreeMap<Long, File> dataFilesMap = new TreeMap<>();
			for (File dataFile : dataFiles) {
				String fileName = dataFile.getName();
				Matcher idMatcher = idPattern.matcher(fileName);
				if (!idMatcher.find()) {
					LOGGER.error("File name {} does not match pattern {}", fileName, editorFileIdRegex.getValue());
					continue;
				}
				String idString = idMatcher.group(1);
				long fileId = Long.parseLong(idString);
				dataFilesMap.put(fileId, dataFile);
			}
			if (skipLast) {
				Long lastKey = dataFilesMap.lastKey();
				dataFilesMap.remove(lastKey);
				LOGGER.info("Remove last file from list: {}.", lastKey);
			}
			return dataFilesMap;
		}

		private List<Observation> compute() throws IOException, ImportException {
			Map.Entry<Long, File> entry = filesIterator.next();
			long fileId = entry.getKey();
			File dataFile = entry.getValue();

			if (alreadyImported(fileId)) {
				return Collections.EMPTY_LIST;
			}

			Instant endTime = Instant.ofEpochMilli(dataFile.lastModified());
			if (previousEndTime == null) {
				previousEndTime = endTime;
				LOGGER.info("Skipping file {}, it is the first, so we have no start time.", dataFile.getName());
				return Collections.EMPTY_LIST;
			}
			Instant startTime = previousEndTime;
			previousEndTime = endTime;

			if (sleepTime > 0) {
				try {
					Thread.sleep(sleepTime);
				} catch (InterruptedException ex) {
					LOGGER.warn("Rude wakeup.", ex);
				}
			}

			LOGGER.info("Reading file id: {} name: {}, from {}, to {}", fileId, dataFile.getName(), startTime, endTime);
			String data = FileUtils.readFileToString(dataFile, "UTF-8");

			List<Observation> observations = docParser.process(null, data);
			LOGGER.info("Generated {} observations.", observations.size());
			TimeObject phenTime = new TimeObject(Interval.of(startTime, endTime));

			Map<String, Object> parameters = new HashMap<>();
			parameters.put("importFileId", fileId);
			for (Observation obs : observations) {
				obs.setPhenomenonTime(phenTime);
				obs.setParameters(parameters);
				parameters.put("resultCount", getResultCount(obs));
			}
			return observations;
		}

		private int getResultCount(Observation obs) {
			Object result = obs.getResult();
			if (result instanceof List) {
				return ((List) result).size();
			}
			return 1;
		}

		@Override
		protected List<Observation> computeNext() {
			if (filesIterator.hasNext()) {
				try {
					return compute();
				} catch (ImportException | IOException exc) {
					LOGGER.error("Failed", exc);
				}
			}
			return endOfData();
		}
	}

}
