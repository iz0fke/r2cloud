package ru.r2cloud.satellite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;

import ru.r2cloud.cloud.LeoSatDataClient;
import ru.r2cloud.cloud.SatnogsClient;
import ru.r2cloud.model.BandFrequency;
import ru.r2cloud.model.Modulation;
import ru.r2cloud.model.Priority;
import ru.r2cloud.model.Satellite;
import ru.r2cloud.model.SatelliteComparator;
import ru.r2cloud.model.SatelliteSource;
import ru.r2cloud.model.SdrType;
import ru.r2cloud.model.Tle;
import ru.r2cloud.model.Transmitter;
import ru.r2cloud.model.TransmitterComparator;
import ru.r2cloud.util.Configuration;

public class SatelliteDao {

	private static final Logger LOG = LoggerFactory.getLogger(SatelliteDao.class);

	private final Configuration config;
	private final LeoSatDataClient client;
	private final SatnogsClient satnogsClient;
	private final List<Satellite> satellites = new ArrayList<>();
	private final Map<String, Satellite> satelliteByName = new HashMap<>();
	private final Map<String, Satellite> satelliteById = new HashMap<>();

	public SatelliteDao(Configuration config, LeoSatDataClient client, SatnogsClient satnogsClient) {
		this.config = config;
		this.client = client;
		this.satnogsClient = satnogsClient;
		reload();
	}

	public synchronized void reload() {
		satellites.clear();
		satelliteByName.clear();
		satelliteById.clear();

		List<Satellite> satnogsSatellites = Collections.emptyList();
		// satnogs data is too generic, load it first and override by handcrafted
		// configs
		if (config.getBoolean("satnogs.satellites")) {
			satnogsSatellites = satnogsClient.loadSatellites();
			for (Satellite cur : satnogsSatellites) {
				if (cur.getPriority().equals(Priority.NORMAL)) {
					satelliteById.put(cur.getId(), cur);
				}
			}
		}

		// default from config
		for (Satellite cur : loadFromConfig(config.getProperty("satellites.meta.location"))) {
			satelliteById.put(cur.getId(), cur);
		}
		if (config.getProperty("r2cloud.apiKey") != null) {
			// new and overrides from server
			for (Satellite cur : client.loadSatellites()) {
				satelliteById.put(cur.getId(), cur);
			}
		}

		// optionally new launches
		if (config.getBoolean("r2cloud.newLaunches")) {
			// satnogs new launch ids are: ABXM-4898-9222-6959-5721
			// leosatdata new launch ids are: R2CLOUD123
			// Can't map them using ids. However leosatdata guarantees the same name for new
			// launches as in satnogs
			// use satellite name for deduplication
			Map<String, Satellite> dedupByName = new HashMap<>();
			for (Satellite cur : satnogsSatellites) {
				if (cur.getPriority().equals(Priority.HIGH)) {
					dedupByName.put(cur.getName().toLowerCase(), cur);
				}
			}
			if (config.getProperty("r2cloud.apiKey") != null) {
				for (Satellite cur : client.loadNewLaunches()) {
					dedupByName.put(cur.getName().toLowerCase(), cur);
				}
			}
			for (Satellite cur : dedupByName.values()) {
				satelliteById.put(cur.getId(), cur);
			}
		}

		satellites.addAll(satelliteById.values());
		List<Transmitter> allTransmitters = new ArrayList<>();
		for (Satellite curSatellite : satellites) {
			normalize(curSatellite);
			allTransmitters.addAll(curSatellite.getTransmitters());
			for (Transmitter curTransmitter : curSatellite.getTransmitters()) {
				switch (curTransmitter.getFraming()) {
				case APT:
					curTransmitter.setInputSampleRate(60_000);
					curTransmitter.setOutputSampleRate(11_025);
					break;
				case LRPT:
					curTransmitter.setInputSampleRate(288_000);
					curTransmitter.setOutputSampleRate(144_000);
					break;
				default:
					// sdr-server supports very narrow bandwidths
					int outputSampleRate = 48_000;
					if (config.getSdrType().equals(SdrType.SDRSERVER)) {
						curTransmitter.setInputSampleRate(outputSampleRate);
						curTransmitter.setOutputSampleRate(outputSampleRate);
					} else if (curTransmitter.getModulation() != null && curTransmitter.getModulation().equals(Modulation.LORA)) {
						// not applicable
						curTransmitter.setInputSampleRate(0);
						curTransmitter.setOutputSampleRate(0);
					} else {
						// some rates better to sample at 50k
						if (curTransmitter.getBaudRates() != null && curTransmitter.getBaudRates().size() > 0 && 50_000 % curTransmitter.getBaudRates().get(0) == 0) {
							outputSampleRate = 50_000;
						}
						// 48k * 5 = 240k - minimum rate rtl-sdr supports
						curTransmitter.setInputSampleRate(outputSampleRate * 5);
						curTransmitter.setOutputSampleRate(outputSampleRate);
					}
					break;
				}
			}
			satelliteByName.put(curSatellite.getName(), curSatellite);
		}
		long sdrServerBandwidth = config.getLong("satellites.sdrserver.bandwidth");
		long bandwidthCrop = config.getLong("satellites.sdrserver.bandwidth.crop");
		Collections.sort(allTransmitters, TransmitterComparator.INSTANCE);

		// bands can be calculated only when all supported transmitters known
		BandFrequency currentBand = null;
		for (Transmitter cur : allTransmitters) {
			long lowerSatelliteFrequency = cur.getFrequency() - cur.getInputSampleRate() / 2;
			long upperSatelliteFrequency = cur.getFrequency() + cur.getInputSampleRate() / 2;
			// first transmitter or upper frequency out of band
			if (currentBand == null || (currentBand.getUpper() - bandwidthCrop) < upperSatelliteFrequency) {
				currentBand = new BandFrequency();
				currentBand.setLower(lowerSatelliteFrequency - bandwidthCrop);
				currentBand.setUpper(currentBand.getLower() + sdrServerBandwidth);
				currentBand.setCenter(currentBand.getLower() + (currentBand.getUpper() - currentBand.getLower()) / 2);
			}
			cur.setFrequencyBand(currentBand);
		}
		Collections.sort(satellites, SatelliteComparator.ID_COMPARATOR);
		printStatsByPriorityAndSource();
	}

	private void normalize(Satellite satellite) {
		// user overrides from UI or manually from config
		String enabled = config.getProperty("satellites." + satellite.getId() + ".enabled");
		if (enabled != null) {
			satellite.setEnabled(Boolean.valueOf(enabled));
		}
		if (satellite.getTle() == null) {
			satellite.setTle(loadTle(satellite, config));
		}
		for (int i = 0; i < satellite.getTransmitters().size(); i++) {
			Transmitter cur = satellite.getTransmitters().get(i);
			cur.setId(satellite.getId() + "-" + String.valueOf(i));
			cur.setEnabled(satellite.isEnabled());
			cur.setPriority(satellite.getPriority());
			cur.setSatelliteId(satellite.getId());
			cur.setStart(satellite.getStart());
			cur.setEnd(satellite.getEnd());
			cur.setTle(satellite.getTle());
		}
	}

	private static List<Satellite> loadFromConfig(String metaLocation) {
		List<Satellite> result = new ArrayList<>();
		JsonArray rawSatellites;
		try (Reader r = new InputStreamReader(SatelliteDao.class.getClassLoader().getResourceAsStream(metaLocation))) {
			rawSatellites = Json.parse(r).asArray();
		} catch (Exception e) {
			LOG.error("unable to parse satellites", e);
			return Collections.emptyList();
		}
		for (int i = 0; i < rawSatellites.size(); i++) {
			Satellite cur = Satellite.fromJson(rawSatellites.get(i).asObject());
			cur.setSource(SatelliteSource.CONFIG);
			result.add(cur);
		}
		return result;
	}

	private static Tle loadTle(Satellite satellite, Configuration config) {
		Path tleFile = config.getSatellitesBasePath().resolve(satellite.getId()).resolve("tle.txt");
		if (!Files.exists(tleFile)) {
			LOG.info("missing tle for {}", satellite.getName());
			return null;
		}
		Tle result;
		try (BufferedReader r = Files.newBufferedReader(tleFile)) {
			String line1 = r.readLine();
			if (line1 == null) {
				return null;
			}
			String line2 = r.readLine();
			if (line2 == null) {
				return null;
			}
			result = new Tle(new String[] { satellite.getName(), line1, line2 });
		} catch (IOException e) {
			LOG.error("unable to load TLE for {}", satellite.getId(), e);
			return null;
		}
		try {
			result.setLastUpdateTime(Files.getLastModifiedTime(tleFile).toMillis());
		} catch (IOException e1) {
			LOG.error("unable to get last modified time", e1);
		}
		return result;
	}

	public synchronized Satellite findByName(String name) {
		return satelliteByName.get(name);
	}

	public synchronized Satellite findById(String id) {
		return satelliteById.get(id);
	}

	public synchronized List<Satellite> findAll() {
		return satellites;
	}

	public void update(Satellite satelliteToEdit) {
		config.setProperty("satellites." + satelliteToEdit.getId() + ".enabled", satelliteToEdit.isEnabled());
		config.update();
	}

	public synchronized List<Satellite> findEnabled() {
		List<Satellite> result = new ArrayList<>();
		for (Satellite cur : satellites) {
			if (cur.isEnabled()) {
				result.add(cur);
			}
		}
		return result;
	}

	private void printStatsByPriorityAndSource() {
		printStatsBySource(Priority.HIGH);
		printStatsBySource(Priority.NORMAL);
	}

	private void printStatsBySource(Priority priority) {
		int total = 0;
		Map<SatelliteSource, Integer> totalBySource = new HashMap<>();
		for (Satellite cur : satellites) {
			if (!cur.getPriority().equals(priority)) {
				continue;
			}
			total++;
			Integer previous = totalBySource.get(cur.getSource());
			if (previous == null) {
				previous = 0;
			}
			totalBySource.put(cur.getSource(), previous + 1);
		}
		LOG.info("{}: {}", priority, total);
		for (Entry<SatelliteSource, Integer> cur : totalBySource.entrySet()) {
			LOG.info("  {}: {}", cur.getKey(), cur.getValue());
		}
	}

}
