/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel;

import com.google.common.base.Charsets;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggerRepository;
import sirius.kernel.commons.Producer;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Value;
import sirius.kernel.commons.ValueHolder;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

/**
 * Used to configure the setup of the SIRIUS framework.
 * <p>
 * An instance of this class is passed into {@link Sirius#start(Setup)} to launch the framework. Alternatively
 * {@link #createAndStartEnvironment(ClassLoader)} can be called which configures and starts SIRIUS based on
 * system properties. This is utilized by the sirius-ipl framework which provides an application container
 * (effectively a main class and some control scripts to make it behave like a daemon or Windows Service).
 * <p>
 * To further customize settings, subclass and override the appropriate methods.
 * <p>
 * By default, this class does the following:
 * <ul>
 * <li>Sets the encoding to UTF-8</li>
 * <li>Sets the DNS cache to 10 seconds - by default this would be 'infinite'</li>
 * <li>Redirects all Java Logging to Log4J</li>
 * <li>Creates either a console or file appender for Log4J (PROD = file, DEV = console)</li>
 * </ul>
 */
public class Setup {

    /**
     * Determines the mode in which the framework should run. This mainly effects logging and the configuration.
     */
    public enum Mode {
        DEV, TEST, PROD
    }

    protected ClassLoader loader;
    protected Mode mode;
    protected Level defaultLevel = Level.INFO;
    protected String consoleLogFormat = "[%d{yyyy-MM-dd'T'HH:mm:ss,SSS}] %-5p [%t%X{flow}] %c - %m%n";

    /**
     * Creates a new setup for the given mode and class loader.
     *
     * @param mode   the mode to run the framework in
     * @param loader the class loader used for component discovery
     */
    public Setup(Mode mode, ClassLoader loader) {
        this.mode = mode;
        this.loader = loader;
    }

    /**
     * Used to set the default log level used by the root logger.
     * <p>
     * Note that each logger can be configured by specifying <tt>logging.[NAME]</tt> in the
     * system configuration
     *
     * @param level the level to use
     * @return the setup itself for fluent method calls
     */
    public Setup withDefaultLogLevel(Level level) {
        this.defaultLevel = level;
        return this;
    }

    /**
     * Specifies the pattern used to format log messages in the console.
     * <p>
     * Refer to {@link org.apache.log4j.PatternLayout} for available options.
     *
     * @param format the template string to use
     * @return the setup itself for fluent method calls
     */
    public Setup withConsoleLogFormat(String format) {
        this.consoleLogFormat = format;
        return this;
    }

    /**
     * Creates and starts a new setup based on system properties.
     * <p>
     * Essentially this is <tt>debug</tt> which switches from PROD to DEV and <tt>console</tt> which enables
     * log output to the console even if running in PROD mode.
     *
     * @param loader the class loader to use
     */
    public static void createAndStartEnvironment(ClassLoader loader) {
        Sirius.start(new Setup(getProperty("debug",
                                           false,
                                           "Determines if debug logs and some safety checks are enabled.").asBoolean(
                false) ? Mode.DEV : Mode.PROD, loader));
    }

    /**
     * Provides a main method for debugging purposes.
     *
     * @param args the command line arguments (currently ignored)
     */
    public static void main(String[] args) {
        Sirius.start(new Setup(Mode.DEV, Setup.class.getClassLoader()));
    }

    /**
     * Initializes the Virtual Machine.
     * <p>
     * This modifies the DNS cache, encoding and logging setup...
     * <p>
     * This method is automatically called by {@link sirius.kernel.Sirius#start(Setup)}
     */
    public void init() {
        setupLogging();
        setupDNSCache();
        setupEncoding();
        outputJVMInfo();
    }

    /**
     * Outputs the name of the underlying JVM to verify that the correct one was used to start the application
     */
    protected void outputJVMInfo() {
        RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
        Sirius.LOG.INFO("%s (%s, %s, %s)", mx.getVmName(), mx.getSpecVersion(), mx.getVmVendor(), mx.getVmVersion());

        OperatingSystemMXBean osx = ManagementFactory.getOperatingSystemMXBean();
        if (osx.getAvailableProcessors() > 1) {
            Sirius.LOG.INFO("%s (%s) on %d CPUs (%s)",
                            osx.getName(),
                            osx.getVersion(),
                            osx.getAvailableProcessors(),
                            osx.getArch());
        } else {
            Sirius.LOG.INFO("%s (%s) on a %s CPU", osx.getName(), osx.getVersion(), osx.getArch());
        }
    }

    /**
     * Returns the loader to use for component discovery.
     *
     * @return the loader to use for component discovery
     */
    public ClassLoader getLoader() {
        return loader;
    }

    /**
     * Returns the mode the framework was started in.
     *
     * @return the mode of the framework
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Sets UTF-8 as default encoding
     */
    protected void setupEncoding() {
        Sirius.LOG.FINE("Setting " + Charsets.UTF_8.name() + " as default encoding (file.encoding)");
        System.setProperty("file.encoding", Charsets.UTF_8.name());
        Sirius.LOG.FINE("Setting " + Charsets.UTF_8.name() + " as default mime encoding (mail.mime.charset)");
        System.setProperty("mail.mime.charset", Charsets.UTF_8.name());
    }

    /**
     * Sets the DNS cache to a sane value.
     * <p>
     * By default java infinitely caches all DNS entries. Will be changed to 10 seconds...
     */
    protected void setupDNSCache() {
        Sirius.LOG.FINE("Setting DNS-Cache to 10 seconds...");
        java.security.Security.setProperty("networkaddress.cache.ttl", "10");
    }

    /**
     * Reads the given system property.
     *
     * @param property     the property to read
     * @param defaultValue the default value to use if no value is present
     * @param description  the description to output
     * @return the contents of the property wrapped as {@link Value}
     */
    protected static Value getProperty(String property, Object defaultValue, String description) {
        Value result = Value.of(System.getProperty(property));
        Sirius.LOG.INFO("Reading property %s (Value: %s, Default: %s) - %s",
                        property,
                        result.replaceEmptyWith("(missing)"),
                        defaultValue,
                        description);

        return result.replaceIfEmpty(() -> defaultValue);
    }

    /**
     * Initializes log4j as logging framework.
     * <p>
     * In development mode, we log everything to the console. In production mode, we use a rolling file appender and
     * log into the logs directory.
     */
    protected void setupLogging() {
        final LoggerRepository repository = Logger.getRootLogger().getLoggerRepository();
        repository.resetConfiguration();
        Logger.getRootLogger().setLevel(defaultLevel);

        ConsoleAppender console = new ConsoleAppender();
        console.setLayout(new PatternLayout(consoleLogFormat));
        console.setThreshold(Level.DEBUG);
        console.activateOptions();
        Logger.getRootLogger().addAppender(console);

        redirectJavaLoggerToLog4j();
    }

    /**
     * Redirects all java.logging output to Log4j
     */
    protected void redirectJavaLoggerToLog4j() {
        final LoggerRepository repository = Logger.getRootLogger().getLoggerRepository();
        java.util.logging.Logger rootLogger = java.util.logging.LogManager.getLogManager().getLogger("");
        // remove old handlers
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        // add our own
        Handler handler = new Handler() {

            private Formatter formatter = new SimpleFormatter();

            @Override
            public void publish(LogRecord record) {
                repository.getLogger(record.getLoggerName() == null ? "unknown" : record.getLoggerName())
                          .log(Log.convertJuliLevel(record.getLevel()),
                               formatter.formatMessage(record),
                               record.getThrown());
            }

            @Override
            public void flush() {
                // Not required
            }

            @Override
            public void close() {
                // Not required
            }
        };
        handler.setLevel(java.util.logging.Level.ALL);
        rootLogger.addHandler(handler);
        rootLogger.setLevel(java.util.logging.Level.INFO);
    }

    @Nullable
    private Config loadConfig(String resourceName, Producer<Config> configMaker, @Nullable Config fallback) {
        try {
            Sirius.LOG.INFO("Loading config: %s", resourceName);
            Config config = configMaker.create();
            if (fallback != null) {
                config = config.withFallback(fallback);
            }
            return config;
        } catch (Exception e) {
            Exceptions.ignore(e);
            Sirius.LOG.WARN("Cannot load %s: %s", resourceName, e.getMessage());
            return fallback;
        }
    }

    /**
     * Loads the main application configuration which is shipped with the app.
     * <p>
     * By default this loads "application.conf" from the classpath. Also "application-*.conf
     * are loaded in case it is splitted into several parts.
     *
     * @return the main application config. This will override all component configs but be overridden by developer,
     * test and instance configs
     */
    @Nonnull
    public Config loadApplicationConfig() {
        final ValueHolder<Config> result = new ValueHolder<>(ConfigFactory.empty());

        // Load component configurations
        Sirius.getClasspath()
              .find(Pattern.compile("application-([^.]*?)\\.conf"))
              .forEach(value -> result.set(loadConfig(value.group(),
                                                      () -> ConfigFactory.parseResources(loader, value.group()),
                                                      result.get())));

        if (Sirius.class.getResource("/application.conf") != null) {
            result.set(loadConfig("application.conf",
                                  () -> ConfigFactory.parseResources(loader, "application.conf"),
                                  result.get()));
        } else {
            Sirius.LOG.INFO("application.conf not present in classpath");
        }

        return result.get();
    }

    /**
     * Applies the test configuration to the given config object.
     * <p>
     * By default this loads and applies "test.conf" from the classpath.
     *
     * @param config the config to enhance
     * @return the enhanced config
     */
    @Nonnull
    public Config applyTestConfig(@Nonnull Config config) {
        if (Sirius.class.getResource("/test.conf") != null) {
            return loadConfig("test.conf", () -> ConfigFactory.parseResources(loader, "test.conf"), config);
        } else {
            Sirius.LOG.INFO("test.conf not present in classpath");
            return config;
        }
    }

    /**
     * Loads the configuration of the current test scenario which will overwrite the settings
     * in <tt>test.conf</tt>.
     *
     * @param scenarioFile the scenario config file to load
     * @param config       the config to enhance
     * @return the enhanced config
     */
    @Nonnull
    public Config applyTestScenarioConfig(@Nullable String scenarioFile, @Nonnull Config config) {
        if (Strings.isEmpty(scenarioFile)) {
            return config;
        }

        return loadConfig(scenarioFile, () -> ConfigFactory.parseResources(loader, scenarioFile), config);
    }

    /**
     * Applies the developer configuration to the given config object.
     * <p>
     * By default this loads and applies "develop.conf" from the file system.
     *
     * @param config the config to enhance
     * @return the enhanced config
     */
    @Nonnull
    public Config applyDeveloperConfig(@Nonnull Config config) {
        String developConfFile = getProperty("sirius_develop_conf",
                                             "develop.conf",
                                             "Determines the filename of the developer config.").asString();
        if (new File(developConfFile).exists()) {
            return loadConfig(developConfFile, () -> ConfigFactory.parseFile(new File(developConfFile)), config);
        } else {
            Sirius.LOG.INFO("%s not present work in directory", developConfFile);
            return config;
        }
    }

    /**
     * Loads the instance configuration which configures the app for the machine it is running on.
     * <p>
     * By default this loads "instance.conf" from the file system
     * <p>
     * This will later be applied to the overall system configuration and will override all other settings.
     *
     * @return the instance configuration or <tt>null</tt> if no config was found.
     */
    @Nullable
    public Config loadInstanceConfig() {
        String instanceConfFile = getProperty("sirius_instance_conf",
                                              "instance.conf",
                                              "Determines the filename of the instance config.").asString();
        if (new File(instanceConfFile).exists()) {
            return loadConfig(instanceConfFile, () -> ConfigFactory.parseFile(new File(instanceConfFile)), null);
        } else {
            Sirius.LOG.INFO("%s not present work in directory", instanceConfFile);
            return null;
        }
    }

    /**
     * Loads <b>and parses</b> the environment.
     * <p>
     * {@link ConfigFactory} by default treats every environment variable as key instead of as path. Therefore we
     * manually load the environment be treating variables as path so that compound keys like sirius.nodeName
     * can be specified.
     *
     * @param config the current config to extend
     * @return the extended config
     */
    @Nonnull
    public Config applyEnvironment(@Nonnull Config config) {
        try {
            return ConfigFactory.parseMap(System.getenv(), "Environment").withFallback(config);
        } catch (Exception e) {
            Sirius.LOG.WARN("An error occured while reading the environment: %s (%s)",
                            e.getMessage(),
                            e.getClass().getName());
            return config;
        }
    }
}
