/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar;

import static com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.common.configuration.PulsarConfigurationLoader.create;
import static org.apache.pulsar.common.configuration.PulsarConfigurationLoader.isComplete;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.apache.bookkeeper.common.component.ComponentStarter;
import org.apache.bookkeeper.common.component.LifecycleComponent;
import org.apache.bookkeeper.common.util.ReflectionUtils;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.replication.AutoRecoveryMain;
import org.apache.bookkeeper.server.conf.BookieConfiguration;
import org.apache.bookkeeper.stats.StatsProvider;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.util.datetime.FixedDateFormat;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.naming.NamespaceBundleSplitAlgorithm;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.util.DirectMemoryUtils;
import org.apache.pulsar.common.util.ShutdownUtil;
import org.apache.pulsar.docs.tools.CmdGenerateDocs;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.functions.worker.service.WorkerServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(description = "broker", showDefaultValues = true, scope = ScopeType.INHERIT)
public class PulsarBrokerStarter {

    private static ServiceConfiguration loadConfig(String configFile) throws Exception {
        try (InputStream inputStream = new FileInputStream(configFile)) {
            ServiceConfiguration config = create(inputStream, ServiceConfiguration.class);
            // it validates provided configuration is completed
            isComplete(config);
            return config;
        }
    }

    @VisibleForTesting
    private static class StarterArguments {
        @Option(names = {"-c", "--broker-conf"}, description = "Configuration file for Broker")
        private String brokerConfigFile = "conf/broker.conf";

        @Option(names = {"-rb", "--run-bookie"}, description = "Run Bookie together with Broker")
        private boolean runBookie = false;

        @Option(names = {"-ra", "--run-bookie-autorecovery"},
                description = "Run Bookie Autorecovery together with broker")
        private boolean runBookieAutoRecovery = false;

        @Option(names = {"-bc", "--bookie-conf"}, description = "Configuration file for Bookie")
        private String bookieConfigFile = "conf/bookkeeper.conf";

        @Option(names = {"-rfw", "--run-functions-worker"}, description = "Run functions worker with Broker")
        private boolean runFunctionsWorker = false;

        @Option(names = {"-fwc", "--functions-worker-conf"}, description = "Configuration file for Functions Worker")
        private String fnWorkerConfigFile = "conf/functions_worker.yml";

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message")
        private boolean help = false;

        @Option(names = {"-g", "--generate-docs"}, description = "Generate docs")
        private boolean generateDocs = false;
    }

    private static ServerConfiguration readBookieConfFile(String bookieConfigFile) throws IllegalArgumentException {
        ServerConfiguration bookieConf = new ServerConfiguration();
        try {
            bookieConf.loadConf(new File(bookieConfigFile).toURI().toURL());
            bookieConf.validate();
            log.info("Using bookie configuration file {}", bookieConfigFile);
        } catch (MalformedURLException e) {
            log.error("Could not open configuration file: {}", bookieConfigFile, e);
            throw new IllegalArgumentException("Could not open configuration file");
        } catch (ConfigurationException e) {
            log.error("Malformed configuration file: {}", bookieConfigFile, e);
            throw new IllegalArgumentException("Malformed configuration file");
        }

        if (bookieConf.getMaxPendingReadRequestPerThread() < bookieConf.getRereplicationEntryBatchSize()) {
            throw new IllegalArgumentException(
                "rereplicationEntryBatchSize should be smaller than " + "maxPendingReadRequestPerThread");
        }
        return bookieConf;
    }

    protected static class BrokerStarter implements Callable<Integer> {
        private ServiceConfiguration brokerConfig;
        private PulsarService pulsarService;
        private LifecycleComponent bookieServer;
        private volatile CompletableFuture<Void> bookieStartFuture;
        private AutoRecoveryMain autoRecoveryMain;
        private StatsProvider bookieStatsProvider;
        private ServerConfiguration bookieConfig;
        private WorkerService functionsWorkerService;
        private WorkerConfig workerConfig;

        private CommandLine commander;

        @ArgGroup(exclusive = false)
        private final StarterArguments starterArguments = new StarterArguments();

        BrokerStarter() {
            commander = new CommandLine(this);
        }

        public int start(String[] args) {
            return commander.execute(args);
        }

        public Integer call() throws Exception {
            if (starterArguments.help) {
                commander.usage(commander.getOut());
                return 0;
            }

            if (starterArguments.generateDocs) {
                CmdGenerateDocs cmd = new CmdGenerateDocs("pulsar");
                cmd.addCommand("broker", commander);
                cmd.run(null);
                return 0;
            }

            // init broker config
            if (isBlank(starterArguments.brokerConfigFile)) {
                commander.usage(commander.getOut());
                throw new IllegalArgumentException("Need to specify a configuration file for broker");
            } else {
                final String filepath = Path.of(starterArguments.brokerConfigFile)
                        .toAbsolutePath().normalize().toString();
                brokerConfig = loadConfig(filepath);
            }

            int maxFrameSize = brokerConfig.getMaxMessageSize() + Commands.MESSAGE_SIZE_FRAME_PADDING;
            if (maxFrameSize >= DirectMemoryUtils.jvmMaxDirectMemory()) {
                throw new IllegalArgumentException("Max message size need smaller than jvm directMemory");
            }

            if (!NamespaceBundleSplitAlgorithm.AVAILABLE_ALGORITHMS.containsAll(
                    brokerConfig.getSupportedNamespaceBundleSplitAlgorithms())) {
                throw new IllegalArgumentException(
                        "The given supported namespace bundle split algorithm has unavailable algorithm. "
                                + "Available algorithms are " + NamespaceBundleSplitAlgorithm.AVAILABLE_ALGORITHMS);
            }

            if (!brokerConfig.getSupportedNamespaceBundleSplitAlgorithms().contains(
                    brokerConfig.getDefaultNamespaceBundleSplitAlgorithm())) {
                throw new IllegalArgumentException("Supported namespace bundle split algorithms "
                        + "must contains the default namespace bundle split algorithm");
            }

            // init functions worker
            if (starterArguments.runFunctionsWorker || brokerConfig.isFunctionsWorkerEnabled()) {
                final String filepath = Path.of(starterArguments.fnWorkerConfigFile)
                        .toAbsolutePath().normalize().toString();
                workerConfig = PulsarService.initializeWorkerConfigFromBrokerConfig(brokerConfig, filepath);
                functionsWorkerService = WorkerServiceLoader.load(workerConfig);
            } else {
                workerConfig = null;
                functionsWorkerService = null;
            }

            // init pulsar service
            pulsarService = new PulsarService(brokerConfig,
                                              workerConfig,
                                              Optional.ofNullable(functionsWorkerService),
                                              (exitCode) -> {
                                                  log.info("Halting broker process with code {}",
                                                           exitCode);
                                                  ShutdownUtil.triggerImmediateForcefulShutdown(exitCode);
                                              });

            // if no argument to run bookie in cmd line, read from pulsar config
            if (!starterArguments.runBookie) {
                starterArguments.runBookie = brokerConfig.isEnableRunBookieTogether();
            }
            if (!starterArguments.runBookieAutoRecovery) {
                starterArguments.runBookieAutoRecovery = brokerConfig.isEnableRunBookieAutoRecoveryTogether();
            }

            if ((starterArguments.runBookie || starterArguments.runBookieAutoRecovery)
                    && isBlank(starterArguments.bookieConfigFile)) {
                commander.usage(commander.getOut());
                throw new IllegalArgumentException("No configuration file for Bookie");
            }

            // init stats provider
            if (starterArguments.runBookie || starterArguments.runBookieAutoRecovery) {
                checkState(isNotBlank(starterArguments.bookieConfigFile),
                    "No configuration file for Bookie");
                final String filepath = Path.of(starterArguments.bookieConfigFile)
                        .toAbsolutePath().normalize().toString();
                bookieConfig = readBookieConfFile(filepath);
                Class<? extends StatsProvider> statsProviderClass = bookieConfig.getStatsProviderClass();
                bookieStatsProvider = ReflectionUtils.newInstance(statsProviderClass);
            } else {
                bookieConfig = null;
                bookieStatsProvider = null;
            }

            // init bookie server
            if (starterArguments.runBookie) {
                Objects.requireNonNull(bookieConfig, "No ServerConfiguration for Bookie");
                Objects.requireNonNull(bookieStatsProvider, "No Stats Provider for Bookie");
                bookieServer = org.apache.bookkeeper.server.Main
                        .buildBookieServer(new BookieConfiguration(bookieConfig));
            } else {
                bookieServer = null;
            }

            // init bookie AutorecoveryMain
            if (starterArguments.runBookieAutoRecovery) {
                Objects.requireNonNull(bookieConfig, "No ServerConfiguration for Bookie Autorecovery");
                autoRecoveryMain = new AutoRecoveryMain(bookieConfig);
            } else {
                autoRecoveryMain = null;
            }

            if (bookieStatsProvider != null) {
                bookieStatsProvider.start(bookieConfig);
                log.info("started bookieStatsProvider.");
            }
            if (bookieServer != null) {
                bookieStartFuture = ComponentStarter.startComponent(bookieServer);
                log.info("started bookieServer.");
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.start();
                log.info("started bookie autoRecoveryMain.");
            }

            pulsarService.start();
            log.info("PulsarService started.");
            return 0;
        }

        public void join() throws InterruptedException {
            if (pulsarService != null) {
                pulsarService.waitUntilClosed();
                try {
                    pulsarService.close();
                } catch (PulsarServerException e) {
                    throw new RuntimeException();
                }
            }

            if (bookieStartFuture != null) {
                bookieStartFuture.join();
                bookieStartFuture = null;
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.join();
            }
        }

        public void shutdown() throws Exception {
            if (null != functionsWorkerService) {
                functionsWorkerService.stop();
                log.info("Shut down functions worker service successfully.");
            }

            if (pulsarService != null) {
                pulsarService.close();
                log.info("Shut down broker service successfully.");
            }

            if (bookieStatsProvider != null) {
                bookieStatsProvider.stop();
                log.info("Shut down bookieStatsProvider successfully.");
            }
            if (bookieServer != null) {
                bookieServer.close();
                log.info("Shut down bookieServer successfully.");
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.shutdown();
                log.info("Shut down autoRecoveryMain successfully.");
            }
        }

        @VisibleForTesting
        CommandLine getCommander() {
            return commander;
        }
    }


    public static void main(String[] args) throws Exception {
        DateFormat dateFormat = new SimpleDateFormat(
            FixedDateFormat.FixedFormat.ISO8601_OFFSET_DATE_TIME_HHMM.getPattern());
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            System.out.println(String.format("%s [%s] error Uncaught exception in thread %s: %s",
                    dateFormat.format(new Date()), thread.getContextClassLoader(),
                    thread.getName(), exception.getMessage()));
            exception.printStackTrace(System.out);
        });

        BrokerStarter starter = new BrokerStarter();
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                try {
                    starter.shutdown();
                } catch (Throwable t) {
                    log.error("Error while shutting down Pulsar service", t);
                } finally {
                    LogManager.shutdown();
                }
            }, "pulsar-service-shutdown")
        );

        PulsarByteBufAllocator.registerOOMListener(oomException -> {
            if (starter.brokerConfig != null && starter.brokerConfig.isSkipBrokerShutdownOnOOM()) {
                log.error("-- Received OOM exception: {}", oomException.getMessage(), oomException);
            } else {
                log.error("-- Shutting down - Received OOM exception: {}", oomException.getMessage(), oomException);
                if (starter.pulsarService != null) {
                    starter.pulsarService.shutdownNow();
                }
            }
        });

        try {
            int start = starter.start(args);
            if (start != 0) {
                System.exit(start);
            }
        } catch (Throwable t) {
            log.error("Failed to start pulsar service.", t);
            ShutdownUtil.triggerImmediateForcefulShutdown();
        } finally {
            starter.join();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PulsarBrokerStarter.class);
}
