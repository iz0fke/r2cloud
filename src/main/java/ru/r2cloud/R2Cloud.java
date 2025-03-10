package ru.r2cloud;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.edu.icm.jlargearrays.ConcurrencyUtils;
import ru.r2cloud.cloud.LeoSatDataClient;
import ru.r2cloud.cloud.LeoSatDataService;
import ru.r2cloud.cloud.SatnogsClient;
import ru.r2cloud.ddns.DDNSClient;
import ru.r2cloud.device.Device;
import ru.r2cloud.device.DeviceManager;
import ru.r2cloud.device.LoraAtDevice;
import ru.r2cloud.device.LoraDevice;
import ru.r2cloud.device.SdrDevice;
import ru.r2cloud.lora.LoraStatus;
import ru.r2cloud.lora.loraat.JSerial;
import ru.r2cloud.lora.loraat.LoraAtClient;
import ru.r2cloud.lora.r2lora.R2loraClient;
import ru.r2cloud.metrics.Metrics;
import ru.r2cloud.model.DeviceConfiguration;
import ru.r2cloud.model.SdrType;
import ru.r2cloud.model.SharedSchedule;
import ru.r2cloud.predict.PredictOreKit;
import ru.r2cloud.satellite.IObservationDao;
import ru.r2cloud.satellite.LoraTransmitterFilter;
import ru.r2cloud.satellite.ObservationDao;
import ru.r2cloud.satellite.ObservationDaoCache;
import ru.r2cloud.satellite.ObservationFactory;
import ru.r2cloud.satellite.SatelliteDao;
import ru.r2cloud.satellite.Schedule;
import ru.r2cloud.satellite.SdrTransmitterFilter;
import ru.r2cloud.satellite.SequentialTimetable;
import ru.r2cloud.satellite.decoder.DecoderService;
import ru.r2cloud.satellite.decoder.Decoders;
import ru.r2cloud.tle.CelestrakClient;
import ru.r2cloud.tle.Housekeeping;
import ru.r2cloud.tle.TleDao;
import ru.r2cloud.util.Clock;
import ru.r2cloud.util.Configuration;
import ru.r2cloud.util.DefaultClock;
import ru.r2cloud.util.ProcessFactory;
import ru.r2cloud.util.ShutdownLoggingManager;
import ru.r2cloud.util.SignedURL;
import ru.r2cloud.util.ThreadPoolFactory;
import ru.r2cloud.util.ThreadPoolFactoryImpl;
import ru.r2cloud.util.Util;
import ru.r2cloud.web.Authenticator;
import ru.r2cloud.web.HttpContoller;
import ru.r2cloud.web.WebServer;
import ru.r2cloud.web.api.AccessToken;
import ru.r2cloud.web.api.Health;
import ru.r2cloud.web.api.PresentationMode;
import ru.r2cloud.web.api.TLE;
import ru.r2cloud.web.api.configuration.Configured;
import ru.r2cloud.web.api.configuration.DDNS;
import ru.r2cloud.web.api.configuration.General;
import ru.r2cloud.web.api.configuration.Integrations;
import ru.r2cloud.web.api.observation.ObservationList;
import ru.r2cloud.web.api.observation.ObservationLoad;
import ru.r2cloud.web.api.observation.ObservationLoadPresentation;
import ru.r2cloud.web.api.observation.ObservationSpectrogram;
import ru.r2cloud.web.api.schedule.ScheduleComplete;
import ru.r2cloud.web.api.schedule.ScheduleFull;
import ru.r2cloud.web.api.schedule.ScheduleList;
import ru.r2cloud.web.api.schedule.ScheduleSave;
import ru.r2cloud.web.api.schedule.ScheduleStart;
import ru.r2cloud.web.api.setup.Restore;
import ru.r2cloud.web.api.setup.Setup;
import ru.r2cloud.web.api.status.MetricsController;
import ru.r2cloud.web.api.status.Overview;

public class R2Cloud {

	static {
		// must be called before any Logger method is used.
		System.setProperty("java.util.logging.manager", ShutdownLoggingManager.class.getName());
	}

	private static final Logger LOG = LoggerFactory.getLogger(R2Cloud.class);

	private static String version;

	private final Map<String, HttpContoller> controllers = new HashMap<>();
	private final WebServer webServer;
	private final Authenticator auth;
	private final Metrics metrics;
	private final AutoUpdate autoUpdate;
	private final DDNSClient ddnsClient;
	private final SatelliteDao satelliteDao;
	private final TleDao tleDao;
	private final Housekeeping houseKeeping;
	private final DecoderService decoderService;
	private final PredictOreKit predict;
	private final ThreadPoolFactory threadFactory;
	private final ObservationFactory observationFactory;
	private final ProcessFactory processFactory;
	private final IObservationDao resultDao;
	private final LeoSatDataService leoSatDataService;
	private final LeoSatDataClient leoSatDataClient;
	private final SatnogsClient satnogsClient;
	private final SpectogramService spectogramService;
	private final Decoders decoders;
	private final SignedURL signed;
	private final DeviceManager deviceManager;

	public R2Cloud(Configuration props, Clock clock) {
		threadFactory = new ThreadPoolFactoryImpl(props.getThreadPoolShutdownMillis());
		processFactory = new ProcessFactory();

		Integer numberOfThreads = props.getInteger("server.fft.threads");
		// if not specified, then number of available processors will be used
		if (numberOfThreads != null) {
			ConcurrencyUtils.setNumberOfThreads(numberOfThreads);
		}

		leoSatDataClient = new LeoSatDataClient(props, clock);
		satnogsClient = new SatnogsClient(props, clock);
		spectogramService = new SpectogramService(props);
		resultDao = new ObservationDaoCache(new ObservationDao(props));
		leoSatDataService = new LeoSatDataService(props, resultDao, leoSatDataClient, spectogramService);
		metrics = new Metrics(props, clock);
		predict = new PredictOreKit(props);
		auth = new Authenticator(props);
		autoUpdate = new AutoUpdate(props);
		ddnsClient = new DDNSClient(props);
		satelliteDao = new SatelliteDao(props);
		tleDao = new TleDao(props);
		signed = new SignedURL(props, clock);
		decoders = new Decoders(predict, props, processFactory);
		decoderService = new DecoderService(props, decoders, resultDao, leoSatDataService, threadFactory, metrics, satelliteDao);
		houseKeeping = new Housekeeping(props, satelliteDao, threadFactory, new CelestrakClient(props), tleDao, satnogsClient, leoSatDataClient, decoderService);

		observationFactory = new ObservationFactory(predict, props);

		deviceManager = new DeviceManager(props, satelliteDao, threadFactory, clock);
		Map<String, SharedSchedule> sharedSchedule = createSharedSchedules(props, observationFactory);
		for (DeviceConfiguration cur : props.getSdrConfigurations()) {
			int numberOfConcurrentObservations = 1;
			if (props.getSdrType().equals(SdrType.SDRSERVER) && cur.getRotatorConfiguration() == null) {
				numberOfConcurrentObservations = 5;
			}
			deviceManager.addDevice(new SdrDevice(cur.getId(), new SdrTransmitterFilter(cur), numberOfConcurrentObservations, observationFactory, threadFactory, clock, cur, resultDao, decoderService, predict, findSharedOrNull(sharedSchedule, cur), props, processFactory));
		}
		for (DeviceConfiguration cur : props.getLoraConfigurations()) {
			R2loraClient client = new R2loraClient(cur.getHostport(), cur.getUsername(), cur.getPassword(), cur.getTimeout());
			populateFrequencies(client.getStatus(), cur);
			deviceManager.addDevice(new LoraDevice(cur.getId(), new LoraTransmitterFilter(cur), 1, observationFactory, threadFactory, clock, cur, resultDao, decoderService, props, predict, findSharedOrNull(sharedSchedule, cur), client));
		}
		for (DeviceConfiguration cur : props.getLoraAtConfigurations()) {
			LoraAtClient client = new LoraAtClient(cur.getHostport(), cur.getTimeout(), new JSerial(), clock);
			populateFrequencies(client.getStatus(), cur);
			deviceManager.addDevice(new LoraAtDevice(cur.getId(), new LoraTransmitterFilter(cur), 1, observationFactory, threadFactory, clock, cur, resultDao, decoderService, props, predict, findSharedOrNull(sharedSchedule, cur), client));
		}

		// setup web server
		index(new Health());
		index(new AccessToken(auth));
		index(new Setup(auth, props));
		index(new Configured(auth, props));
		index(new Restore(auth));
		index(new MetricsController(signed, metrics));
		index(new Overview(props, deviceManager));
		index(new General(props, autoUpdate));
		index(new DDNS(props, ddnsClient));
		index(new TLE(satelliteDao, tleDao));
		index(new Integrations(props));
		index(new ObservationSpectrogram(resultDao, spectogramService, signed));
		index(new ObservationList(satelliteDao, resultDao));
		index(new ObservationLoad(resultDao, signed, satelliteDao));
		index(new ObservationLoadPresentation(props, resultDao, signed, satelliteDao));
		index(new ScheduleList(satelliteDao, deviceManager));
		index(new ScheduleSave(satelliteDao, deviceManager));
		index(new ScheduleStart(satelliteDao, deviceManager));
		index(new ScheduleComplete(deviceManager));
		index(new PresentationMode(props, satelliteDao, resultDao, deviceManager));
		index(new ScheduleFull(deviceManager));
		webServer = new WebServer(props, controllers, auth, signed);
	}

	public void start() {
		ddnsClient.start();
		decoderService.start();
		houseKeeping.start();
		// device manager should start after tle (it uses TLE to schedule
		// observations)
		deviceManager.start();
		metrics.start();
		webServer.start();
		LOG.info("=================================");
		LOG.info("=========== started =============");
		LOG.info("=================================");
	}

	public void stop() {
		webServer.stop();
		metrics.stop();
		deviceManager.stop();
		houseKeeping.stop();
		decoderService.stop();
		ddnsClient.stop();
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			LOG.info("invalid arguments. expected: config.properties");
			return;
		}
		version = readVersion();
		R2Cloud app;
		String userPropertiesFilename = System.getProperty("user.home") + File.separator + ".r2cloud";
		try (InputStream is = new FileInputStream(args[0])) {
			Configuration props = new Configuration(is, userPropertiesFilename, FileSystems.getDefault());
			app = new R2Cloud(props, new DefaultClock());
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					LOG.info("stopping");
					app.stop();
				} catch (Exception e) {
					LOG.error("unable to gracefully shutdown", e);
				} finally {
					LOG.info("=========== stopped =============");
					ShutdownLoggingManager.resetFinally();
				}
			}
		});
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {

			@Override
			public void uncaughtException(Thread t, Throwable e) {
				LOG.error("UncaughtException at: " + t.getName(), e);
			}
		});
		try {
			app.start();
		} catch (Exception e) {
			LOG.error("unable to start", e);
			// this will execute ShutdownHook and graceful shutdown
			System.exit(1);
		}
	}

	private static String readVersion() {
		InputStream is = null;
		try {
			is = R2Cloud.class.getClassLoader().getResourceAsStream("version.properties");
			if (is != null) {
				Properties props = new Properties();
				props.load(is);
				String result = props.getProperty("version", null);
				if (result != null) {
					return result;
				}
			}
		} catch (Exception e) {
			LOG.info("unable to read version. fallback to unknown. reason: {}", e.getMessage());
		} finally {
			Util.closeQuietly(is);
		}
		return null;
	}

	private void index(HttpContoller controller) {
		HttpContoller previous = controllers.put(controller.getRequestMappingURL(), controller);
		if (previous != null) {
			throw new IllegalArgumentException("duplicate controller has been registerd: " + controller.getClass().getSimpleName() + " previous: " + previous.getClass().getSimpleName());
		}
	}

	private static void populateFrequencies(LoraStatus status, DeviceConfiguration config) {
		if (status.getConfigs() == null) {
			return;
		}
		for (ru.r2cloud.lora.ModulationConfig cur : status.getConfigs()) {
			if (!cur.getName().equalsIgnoreCase("lora")) {
				continue;
			}
			config.setMinimumFrequency((long) (cur.getMinFrequency() * 1_000_000));
			config.setMaximumFrequency((long) (cur.getMaxFrequency() * 1_000_000));
			return;
		}
	}

	private static Map<String, SharedSchedule> createSharedSchedules(Configuration props, ObservationFactory factory) {
		Map<String, SharedSchedule> temp = new HashMap<>();
		List<DeviceConfiguration> allDeviceConfigurations = new ArrayList<>();
		allDeviceConfigurations.addAll(props.getSdrConfigurations());
		allDeviceConfigurations.addAll(props.getLoraConfigurations());
		allDeviceConfigurations.addAll(props.getLoraAtConfigurations());
		for (DeviceConfiguration cur : allDeviceConfigurations) {
			if (cur.getRotatorConfiguration() == null) {
				continue;
			}
			SharedSchedule previous = temp.get(cur.getRotatorConfiguration().getId());
			if (previous == null) {
				previous = new SharedSchedule();
				temp.put(cur.getRotatorConfiguration().getId(), previous);
			}
			previous.getDevicesIds().add(cur.getId());
		}
		Map<String, SharedSchedule> result = new HashMap<>();
		for (Map.Entry<String, SharedSchedule> cur : temp.entrySet()) {
			if (cur.getValue().getDevicesIds().size() < 2) {
				continue;
			}
			cur.getValue().setSchedule(new Schedule(new SequentialTimetable(Device.PARTIAL_TOLERANCE_MILLIS), factory));
			LOG.info("[{}] using shared rotator for: {}", cur.getKey(), cur.getValue().getDevicesIds());
			result.put(cur.getKey(), cur.getValue());
		}
		return result;
	}

	private static Schedule findSharedOrNull(Map<String, SharedSchedule> sharedSchedule, DeviceConfiguration config) {
		if (config.getRotatorConfiguration() == null) {
			return null;
		}
		SharedSchedule shared = sharedSchedule.get(config.getRotatorConfiguration().getId());
		if (shared == null) {
			return null;
		}
		if (shared.getDevicesIds().contains(config.getId())) {
			return shared.getSchedule();
		}
		return null;
	}

	public static String getVersion() {
		if (version == null) {
			return "unknown";
		}
		return version;
	}
}