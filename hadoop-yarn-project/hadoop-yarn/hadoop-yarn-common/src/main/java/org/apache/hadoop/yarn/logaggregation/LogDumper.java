/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.logaggregation;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogKey;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogReader;
import org.apache.hadoop.yarn.util.ConverterUtils;

import com.google.common.annotations.VisibleForTesting;

@Public
@Evolving
public class LogDumper extends Configured implements Tool {

  private static final String CONTAINER_ID_OPTION = "containerId";
  private static final String APPLICATION_ID_OPTION = "applicationId";
  private static final String NODE_ADDRESS_OPTION = "nodeAddress";
  private static final String APP_OWNER_OPTION = "appOwner";

  @Override
  public int run(String[] args) throws Exception {

    Options opts = new Options();
    opts.addOption(APPLICATION_ID_OPTION, true, "ApplicationId (required)");
    opts.addOption(CONTAINER_ID_OPTION, true,
      "ContainerId (must be specified if node address is specified)");
    opts.addOption(NODE_ADDRESS_OPTION, true, "NodeAddress in the format "
      + "nodename:port (must be specified if container id is specified)");
    opts.addOption(APP_OWNER_OPTION, true,
      "AppOwner (assumed to be current user if not specified)");
    opts.getOption(APPLICATION_ID_OPTION).setArgName("Application ID");
    opts.getOption(CONTAINER_ID_OPTION).setArgName("Container ID");
    opts.getOption(NODE_ADDRESS_OPTION).setArgName("Node Address");
    opts.getOption(APP_OWNER_OPTION).setArgName("Application Owner");

    Options printOpts = new Options();
    printOpts.addOption(opts.getOption(CONTAINER_ID_OPTION));
    printOpts.addOption(opts.getOption(NODE_ADDRESS_OPTION));
    printOpts.addOption(opts.getOption(APP_OWNER_OPTION));

    if (args.length < 1) {
      printHelpMessage(printOpts);
      return -1;
    }

    CommandLineParser parser = new GnuParser();
    String appIdStr = null;
    String containerIdStr = null;
    String nodeAddress = null;
    String appOwner = null;
    try {
      CommandLine commandLine = parser.parse(opts, args, true);
      appIdStr = commandLine.getOptionValue(APPLICATION_ID_OPTION);
      containerIdStr = commandLine.getOptionValue(CONTAINER_ID_OPTION);
      nodeAddress = commandLine.getOptionValue(NODE_ADDRESS_OPTION);
      appOwner = commandLine.getOptionValue(APP_OWNER_OPTION);
    } catch (ParseException e) {
      System.out.println("options parsing failed: " + e.getMessage());
      printHelpMessage(printOpts);
      return -1;
    }

    if (appIdStr == null) {
      System.out.println("ApplicationId cannot be null!");
      printHelpMessage(printOpts);
      return -1;
    }

    RecordFactory recordFactory =
        RecordFactoryProvider.getRecordFactory(getConf());
    ApplicationId appId =
        ConverterUtils.toApplicationId(recordFactory, appIdStr);

    if (appOwner == null || appOwner.isEmpty()) {
      appOwner = UserGroupInformation.getCurrentUser().getShortUserName();
    }
    int resultCode = 0;
    if (containerIdStr == null && nodeAddress == null) {
      resultCode = dumpAllContainersLogs(appId, appOwner, System.out);
    } else if ((containerIdStr == null && nodeAddress != null)
        || (containerIdStr != null && nodeAddress == null)) {
      System.out.println("ContainerId or NodeAddress cannot be null!");
      printHelpMessage(printOpts);
      resultCode = -1;
    } else {
      Path remoteRootLogDir =
        new Path(getConf().get(YarnConfiguration.NM_REMOTE_APP_LOG_DIR,
            YarnConfiguration.DEFAULT_NM_REMOTE_APP_LOG_DIR));
      AggregatedLogFormat.LogReader reader =
          new AggregatedLogFormat.LogReader(getConf(),
              LogAggregationUtils.getRemoteNodeLogFileForApp(
                  remoteRootLogDir,
                  appId,
                  appOwner,
                  ConverterUtils.toNodeId(nodeAddress),
                  LogAggregationUtils.getRemoteNodeLogDirSuffix(getConf())));
      resultCode = dumpAContainerLogs(containerIdStr, reader, System.out);
    }

    return resultCode;
  }

  @Private
  @VisibleForTesting
  public int dumpAContainersLogs(String appId, String containerId,
      String nodeId, String jobOwner) throws IOException {
    Path remoteRootLogDir =
        new Path(getConf().get(YarnConfiguration.NM_REMOTE_APP_LOG_DIR,
            YarnConfiguration.DEFAULT_NM_REMOTE_APP_LOG_DIR));
    String suffix = LogAggregationUtils.getRemoteNodeLogDirSuffix(getConf());
    Path logPath = LogAggregationUtils.getRemoteNodeLogFileForApp(
        remoteRootLogDir, ConverterUtils.toApplicationId(appId), jobOwner,
        ConverterUtils.toNodeId(nodeId), suffix);
    AggregatedLogFormat.LogReader reader;
    try {
      reader = new AggregatedLogFormat.LogReader(getConf(), logPath);
    } catch (FileNotFoundException fnfe) {
      System.out.println("Logs not available at " + logPath.toString());
      System.out.println(
          "Log aggregation has not completed or is not enabled.");
      return -1;
    }
    return dumpAContainerLogs(containerId, reader, System.out);
  }

  private int dumpAContainerLogs(String containerIdStr,
      AggregatedLogFormat.LogReader reader, PrintStream out)
      throws IOException {
    DataInputStream valueStream;
    LogKey key = new LogKey();
    valueStream = reader.next(key);

    while (valueStream != null && !key.toString().equals(containerIdStr)) {
      // Next container
      key = new LogKey();
      valueStream = reader.next(key);
    }

    if (valueStream == null) {
      System.out.println("Logs for container " + containerIdStr
          + " are not present in this log-file.");
      return -1;
    }

    while (true) {
      try {
        LogReader.readAContainerLogsForALogType(valueStream, out);
      } catch (EOFException eof) {
        break;
      }
    }
    return 0;
  }

  private int dumpAllContainersLogs(ApplicationId appId, String appOwner,
      PrintStream out) throws IOException {
    Path remoteRootLogDir =
        new Path(getConf().get(YarnConfiguration.NM_REMOTE_APP_LOG_DIR,
            YarnConfiguration.DEFAULT_NM_REMOTE_APP_LOG_DIR));
    String user = appOwner;
    String logDirSuffix =
        LogAggregationUtils.getRemoteNodeLogDirSuffix(getConf());
    //TODO Change this to get a list of files from the LAS.
    Path remoteAppLogDir =
        LogAggregationUtils.getRemoteAppLogDir(remoteRootLogDir, appId, user,
            logDirSuffix);
    RemoteIterator<FileStatus> nodeFiles;
    try {
      nodeFiles = FileContext.getFileContext().listStatus(remoteAppLogDir);
    } catch (FileNotFoundException fnf) {
      System.out.println("Logs not available at "
          + remoteAppLogDir.toString());
      System.out.println(
          "Log aggregation has not completed or is not enabled.");
      return -1;
    }
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      AggregatedLogFormat.LogReader reader =
          new AggregatedLogFormat.LogReader(getConf(),
              new Path(remoteAppLogDir, thisNodeFile.getPath().getName()));
      try {

        DataInputStream valueStream;
        LogKey key = new LogKey();
        valueStream = reader.next(key);

        while (valueStream != null) {
          String containerString = "\n\nContainer: " + key + " on " + thisNodeFile.getPath().getName();
          out.println(containerString);
          out.println(StringUtils.repeat("=", containerString.length()));
          while (true) {
            try {
              LogReader.readAContainerLogsForALogType(valueStream, out);
            } catch (EOFException eof) {
              break;
            }
          }

          // Next container
          key = new LogKey();
          valueStream = reader.next(key);
        }
      } finally {
        reader.close();
      }
    }
    return 0;
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = new YarnConfiguration();
    LogDumper logDumper = new LogDumper();
    logDumper.setConf(conf);
    int exitCode = logDumper.run(args);
    System.exit(exitCode);
  }

  private void printHelpMessage(Options options) {
    System.out.println("Retrieve logs for completed YARN applications.");
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("yarn logs -applicationId <application ID> [OPTIONS]", new Options());
    formatter.setSyntaxPrefix("");
    formatter.printHelp("general options are:", options);
  }
}
