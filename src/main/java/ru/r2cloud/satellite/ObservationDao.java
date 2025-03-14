package ru.r2cloud.satellite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import ru.r2cloud.FilenameComparator;
import ru.r2cloud.model.Observation;
import ru.r2cloud.model.ObservationComparator;
import ru.r2cloud.util.Configuration;
import ru.r2cloud.util.Util;

public class ObservationDao implements IObservationDao {

	private static final String DEST_ALREADY_EXIST_MESSAGE = "unable to save. dest already exist: {}";
	private static final String SPECTOGRAM_FILENAME = "spectogram.png";
	private static final String DATA_FILENAME = "data.bin";
	private static final String IMAGE_FILENAME = "a.jpg";
	private static final String META_FILENAME = "meta.json";
	private static final String OUTPUT_WAV_FILENAME = "output.wav";
	private static final String OUTPUT_RAW_FILENAME_GZIPPED = "output.raw.gz";
	private static final String OUTPUT_RAW_FILENAME = "output.raw";

	private static final Logger LOG = LoggerFactory.getLogger(ObservationDao.class);
	private static final Map<String, List<Observation>> IN_FLIGHT_OBSERVATIONS = new HashMap<>();

	private final Path basepath;
	private final int maxCount;
	private final int maxCountRawData;
	private final TimeSizeRetention retention;

	public ObservationDao(Configuration config) {
		this.basepath = config.getSatellitesBasePath();
		this.maxCount = config.getInteger("scheduler.data.retention.count");
		this.maxCountRawData = config.getInteger("scheduler.data.retention.raw.count");
		if (maxCountRawData > maxCount) {
			LOG.error("scheduler.data.retention.raw.count: {} is more than scheduler.data.retention.count: {}. did you mean the opposite?", maxCountRawData, maxCount);
		}
		Long maxRetentionSize = config.getLong("scheduler.data.retention.maxSizeBytes");
		if (maxRetentionSize != null) {
			LOG.info("retention: keep last {}Mb of observations", (maxRetentionSize / 1024 / 1024));
			retention = new TimeSizeRetention(maxRetentionSize, basepath);
		} else {
			LOG.info("retention: keep last {} observations per satellite and last {} raw data", maxCount, maxCountRawData);
			retention = null;
		}
	}

	@Override
	public List<Observation> findAll() {
		if (!Files.exists(basepath)) {
			return Collections.emptyList();
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(basepath)) {
			List<Observation> result = new ArrayList<>();
			for (Path curSatellite : ds) {
				result.addAll(findAllBySatelliteId(curSatellite.getFileName().toString()));
			}
			return result;
		} catch (IOException e) {
			LOG.error("unable to find all", e);
			return Collections.emptyList();
		}
	}

	@Override
	public List<Observation> findAllBySatelliteId(String satelliteId) {
		List<Observation> result = new ArrayList<>();
		result.addAll(loadFromDisk(satelliteId));
		synchronized (IN_FLIGHT_OBSERVATIONS) {
			List<Observation> inFlight = IN_FLIGHT_OBSERVATIONS.get(satelliteId);
			if (inFlight != null) {
				result.addAll(inFlight);
			}
		}
		Collections.sort(result, ObservationComparator.INSTANCE);
		return result;
	}

	private List<Observation> loadFromDisk(String satelliteId) {
		Path dataRoot = basepath.resolve(satelliteId).resolve("data");
		if (!Files.exists(dataRoot)) {
			return Collections.emptyList();
		}
		List<Path> observations;
		try {
			observations = Util.toList(Files.newDirectoryStream(dataRoot));
		} catch (IOException e) {
			LOG.error("unable to load observations", e);
			return Collections.emptyList();
		}
		List<Observation> result = new ArrayList<>(observations.size());
		for (Path curDirectory : observations) {
			Observation cur = find(satelliteId, curDirectory);
			// some directories might be corrupted
			if (cur == null) {
				continue;
			}
			result.add(cur);
		}
		return result;
	}

	@Override
	public Observation find(String satelliteId, String observationId) {
		synchronized (IN_FLIGHT_OBSERVATIONS) {
			List<Observation> inFlight = IN_FLIGHT_OBSERVATIONS.get(satelliteId);
			if (inFlight != null) {
				for (Observation cur : inFlight) {
					if (cur.getId().equalsIgnoreCase(observationId)) {
						return cur;
					}
				}
			}
		}
		Path baseDirectory = basepath.resolve(satelliteId).resolve("data").resolve(observationId);
		if (!Files.exists(baseDirectory)) {
			return null;
		}
		return find(satelliteId, baseDirectory);
	}

	private static Observation find(String satelliteId, Path curDirectory) {
		Path dest = curDirectory.resolve(META_FILENAME);
		if (!Files.exists(dest)) {
			return null;
		}
		Observation full;
		try (BufferedReader r = Files.newBufferedReader(dest)) {
			JsonObject meta = Json.parse(r).asObject();
			full = Observation.fromJson(meta);
		} catch (Exception e) {
			LOG.error("unable to load meta from {}", dest, e);
			return null;
		}

		Path a = curDirectory.resolve(IMAGE_FILENAME);
		if (Files.exists(a)) {
			full.setImagePath(a.toFile());
			full.setaURL("/api/v1/admin/static/satellites/" + satelliteId + "/data/" + full.getId() + "/" + IMAGE_FILENAME);
		}
		Path data = curDirectory.resolve(DATA_FILENAME);
		if (Files.exists(data)) {
			full.setDataPath(data.toFile());
			full.setDataURL("/api/v1/admin/static/satellites/" + satelliteId + "/data/" + full.getId() + "/" + DATA_FILENAME);
		}
		Path rawPath = resolveRawPath(curDirectory);
		if (Files.exists(rawPath)) {
			full.setRawPath(rawPath.toFile());
			full.setRawURL("/api/v1/admin/static/satellites/" + satelliteId + "/data/" + full.getId() + "/" + rawPath.getFileName());
		}
		Path spectogram = curDirectory.resolve(SPECTOGRAM_FILENAME);
		if (Files.exists(spectogram)) {
			full.setSpectogramPath(spectogram.toFile());
			full.setSpectogramURL("/api/v1/admin/static/satellites/" + satelliteId + "/data/" + full.getId() + "/" + SPECTOGRAM_FILENAME);
		}

		return full;
	}

	private static Path resolveRawPath(Path baseDir) {
		Path result = baseDir.resolve(OUTPUT_WAV_FILENAME);
		if (Files.exists(result)) {
			return result;
		}
		result = baseDir.resolve(OUTPUT_RAW_FILENAME_GZIPPED);
		if (Files.exists(result)) {
			return result;
		}
		return baseDir.resolve(OUTPUT_RAW_FILENAME);
	}

	@Override
	public File saveImage(String satelliteId, String observationId, File a) {
		Path dest = getObservationBasepath(satelliteId, observationId).resolve(IMAGE_FILENAME);
		if (Files.exists(dest)) {
			LOG.info(DEST_ALREADY_EXIST_MESSAGE, dest.toAbsolutePath());
			return null;
		}
		if (!a.renameTo(dest.toFile())) {
			return null;
		}
		return dest.toFile();
	}

	@Override
	public File saveData(String satelliteId, String observationId, File a) {
		Path dest = getObservationBasepath(satelliteId, observationId).resolve(DATA_FILENAME);
		if (Files.exists(dest)) {
			LOG.info(DEST_ALREADY_EXIST_MESSAGE, dest.toAbsolutePath());
			return null;
		}
		if (!a.renameTo(dest.toFile())) {
			return null;
		}
		return dest.toFile();
	}

	@Override
	public File saveSpectogram(String satelliteId, String observationId, File a) {
		Path dest = getObservationBasepath(satelliteId, observationId).resolve(SPECTOGRAM_FILENAME);
		if (Files.exists(dest)) {
			LOG.info(DEST_ALREADY_EXIST_MESSAGE, dest.toAbsolutePath());
			return null;
		}
		if (!a.renameTo(dest.toFile())) {
			return null;
		}
		return dest.toFile();
	}

	@Override
	public void insert(Observation observation) {
		synchronized (IN_FLIGHT_OBSERVATIONS) {
			List<Observation> inFlight = IN_FLIGHT_OBSERVATIONS.get(observation.getSatelliteId());
			if (inFlight == null) {
				inFlight = new ArrayList<>();
				IN_FLIGHT_OBSERVATIONS.put(observation.getSatelliteId(), inFlight);
			}
			inFlight.add(observation);
		}
	}

	@Override
	public void cancel(Observation observation) {
		synchronized (IN_FLIGHT_OBSERVATIONS) {
			List<Observation> inFlight = IN_FLIGHT_OBSERVATIONS.get(observation.getSatelliteId());
			if (inFlight != null) {
				Iterator<Observation> it = inFlight.iterator();
				while (it.hasNext()) {
					Observation cur = it.next();
					if (cur.getId().equalsIgnoreCase(observation.getId())) {
						it.remove();
						break;
					}
				}
				if (inFlight.isEmpty()) {
					IN_FLIGHT_OBSERVATIONS.remove(observation.getSatelliteId());
				}
			}
		}
	}

	@Override
	public File update(Observation observation, File rawFile) {
		synchronized (IN_FLIGHT_OBSERVATIONS) {
			cancel(observation);
			if (retention == null) {
				cleanupPreviousObservations(observation);
			}

			Path observationBasePath = getObservationBasepath(observation);
			if (!Util.initDirectory(observationBasePath)) {
				return null;
			}

			if (!update(observation)) {
				return null;
			}
		}
		return insertRawFile(observation, rawFile);
	}

	private void cleanupPreviousObservations(Observation observation) {
		try {
			Path satelliteBasePath = basepath.resolve(observation.getSatelliteId()).resolve("data");
			if (Files.exists(satelliteBasePath)) {
				List<Path> dataDirs = Util.toList(Files.newDirectoryStream(satelliteBasePath));
				Collections.sort(dataDirs, FilenameComparator.INSTANCE_ASC);
				// the new observation will be added after the cleanup
				// see below
				int currentPlusNew = dataDirs.size() + 1;
				if (currentPlusNew > maxCountRawData) {
					for (int i = 0; i < (currentPlusNew - maxCountRawData); i++) {
						Util.deleteQuietly(resolveRawPath(dataDirs.get(i)));
					}
				}
				if (currentPlusNew > maxCount) {
					for (int i = 0; i < (currentPlusNew - maxCount); i++) {
						Util.deleteDirectory(dataDirs.get(i));
					}
				}
			}
		} catch (IOException e) {
			LOG.error("unable to cleanup old observations", e);
		}
	}

	private File insertRawFile(Observation observation, File rawFile) {
		String filename;
		if (rawFile.getName().endsWith("wav")) {
			filename = OUTPUT_WAV_FILENAME;
		} else if (rawFile.getName().endsWith(".gz")) {
			filename = OUTPUT_RAW_FILENAME_GZIPPED;
		} else {
			filename = OUTPUT_RAW_FILENAME;
		}
		Path observationBasepath = getObservationBasepath(observation);
		Path dest = observationBasepath.resolve(filename);
		if (!Util.initDirectory(dest.getParent())) {
			return null;
		}
		if (Files.exists(dest)) {
			LOG.info(DEST_ALREADY_EXIST_MESSAGE, dest.toAbsolutePath());
			return null;
		}
		if (!rawFile.renameTo(dest.toFile())) {
			LOG.error("unable to save file from {} to {}. Check src and dst are on the same filesystem", rawFile.getAbsolutePath(), dest.toFile().getAbsolutePath());
			return null;
		}
		if (retention != null) {
			// re-index and cleanup on new raw File
			// rawFile is the biggest file in the observation
			retention.indexAndCleanup(observationBasepath);
		}
		return dest.toFile();
	}

	@Override
	public boolean update(Observation cur) {
		JsonObject meta = cur.toJson(null);
		Path temp = getObservationBasepath(cur).resolve(META_FILENAME + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(temp)) {
			w.append(meta.toString());
		} catch (IOException e) {
			LOG.error("unable to write meta", e);
			return false;
		}
		Path dest = getObservationBasepath(cur).resolve(META_FILENAME);
		try {
			Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			LOG.error("unable to write meta", e);
			return false;
		}
	}

	private Path getObservationBasepath(Observation observation) {
		return getObservationBasepath(observation.getSatelliteId(), observation.getId());
	}

	private Path getObservationBasepath(String satelliteId, String observationId) {
		return basepath.resolve(satelliteId).resolve("data").resolve(observationId);
	}

}
