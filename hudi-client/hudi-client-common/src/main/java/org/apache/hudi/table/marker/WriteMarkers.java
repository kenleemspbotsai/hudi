/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.marker;

import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.IOType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;

import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Set;

/**
 * Operates on markers for a given write action (commit, delta commit, compaction).
 *
 * This abstract class provides abstract methods of different marker operations, so that
 * different marker write mechanism can be implemented.
 */
public abstract class WriteMarkers implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(WriteMarkers.class);

  protected final String basePath;
  protected final transient Path markerDirPath;
  protected final String instantTime;

  public WriteMarkers(String basePath, String markerFolderPath, String instantTime) {
    this.basePath = basePath;
    this.markerDirPath = new Path(markerFolderPath);
    this.instantTime = instantTime;
  }

  /**
   * Creates a marker without checking if the marker already exists.
   *
   * @param partitionPath partition path in the table.
   * @param dataFileName  data file name.
   * @param type          write IO type.
   * @return the marker path.
   */
  public Option<Path> create(String partitionPath, String dataFileName, IOType type) {
    return create(partitionPath, dataFileName, type, false);
  }

  /**
   * Creates a marker without checking if the marker already exists.
   * This can invoke marker-based early conflict detection when enabled for multi-writers.
   *
   * @param partitionPath  partition path in the table
   * @param dataFileName   data file name
   * @param type           write IO type
   * @param writeConfig    Hudi write configs.
   * @param fileId         File ID.
   * @param activeTimeline Active timeline for the write operation.
   * @return the marker path.
   */
  public Option<Path> create(String partitionPath, String dataFileName, IOType type, HoodieWriteConfig writeConfig,
                             String fileId, HoodieActiveTimeline activeTimeline) {
    if (writeConfig.getWriteConcurrencyMode().supportsOptimisticConcurrencyControl()
        && writeConfig.isEarlyConflictDetectionEnable()) {
      HoodieTimeline pendingCompactionTimeline = activeTimeline.filterPendingCompactionTimeline();
      HoodieTimeline pendingReplaceTimeline = activeTimeline.filterPendingReplaceTimeline();
      // TODO If current is compact or clustering then create marker directly without early conflict detection.
      // Need to support early conflict detection between table service and common writers.
      if (pendingCompactionTimeline.containsInstant(instantTime) || pendingReplaceTimeline.containsInstant(instantTime)) {
        return create(partitionPath, dataFileName, type, false);
      }
      return createWithEarlyConflictDetection(partitionPath, dataFileName, type, false, writeConfig, fileId, activeTimeline);
    }
    return create(partitionPath, dataFileName, type, false);
  }

  /**
   * Creates a marker if the marker does not exist.
   *
   * @param partitionPath partition path in the table
   * @param dataFileName data file name
   * @param type write IO type
   * @return the marker path or empty option if already exists
   */
  public Option<Path> createIfNotExists(String partitionPath, String dataFileName, IOType type) {
    return create(partitionPath, dataFileName, type, true);
  }

  /**
   * Quietly deletes the marker directory.
   *
   * @param context {@code HoodieEngineContext} instance.
   * @param parallelism parallelism for deleting the marker files in the directory.
   */
  public void quietDeleteMarkerDir(HoodieEngineContext context, int parallelism) {
    try {
      context.setJobStatus(this.getClass().getSimpleName(), "Deleting marker directory: " + basePath);
      deleteMarkerDir(context, parallelism);
    } catch (Exception e) {
      LOG.warn("Error deleting marker directory for instant " + instantTime, e);
    }
  }

  /**
   * Strips the marker file suffix from the input path, i.e., ".marker.[IO_type]".
   *
   * @param path  file path
   * @return Stripped path
   */
  public static String stripMarkerSuffix(String path) {
    return path.substring(0, path.indexOf(HoodieTableMetaClient.MARKER_EXTN));
  }

  /**
   * Gets the marker file name, in the format of "[data_file_name].marker.[IO_type]".
   *
   * @param dataFileName data file name
   * @param type IO type
   * @return the marker file name
   */
  protected String getMarkerFileName(String dataFileName, IOType type) {
    return String.format("%s%s.%s", dataFileName, HoodieTableMetaClient.MARKER_EXTN, type.name());
  }

  /**
   * Returns the marker path. Would create the partition path first if not exists
   *
   * @param partitionPath The partition path
   * @param dataFileName  The data file name
   * @param type          The IO type
   * @return path of the marker file
   */
  protected Path getMarkerPath(String partitionPath, String dataFileName, IOType type) {
    Path path = FSUtils.getPartitionPath(markerDirPath, partitionPath);
    String markerFileName = getMarkerFileName(dataFileName, type);
    return new Path(path, markerFileName);
  }

  /**
   * Deletes the marker directory.
   *
   * @param context {@code HoodieEngineContext} instance.
   * @param parallelism parallelism for deleting the marker files in the directory.
   * @return {@true} if successful; {@false} otherwise.
   */
  public abstract boolean deleteMarkerDir(HoodieEngineContext context, int parallelism);

  /**
   * @return {@true} if the marker directory exists in the file system; {@false} otherwise.
   * @throws IOException
   */
  public abstract boolean doesMarkerDirExist() throws IOException;

  /**
   * @param context {@code HoodieEngineContext} instance.
   * @param parallelism parallelism for reading the marker files in the directory.
   * @return all the data file paths of write IO type "CREATE" and "MERGE"
   * @throws IOException
   */
  public abstract Set<String> createdAndMergedDataPaths(HoodieEngineContext context, int parallelism) throws IOException;

  /**
   * @return all the marker paths
   * @throws IOException
   */
  public abstract Set<String> allMarkerFilePaths() throws IOException;

  /**
   * Creates a marker.
   *
   * @param partitionPath  partition path in the table
   * @param dataFileName  data file name
   * @param type write IO type
   * @param checkIfExists whether to check if the marker already exists
   * @return the marker path or empty option if already exists and {@code checkIfExists} is true
   */
  abstract Option<Path> create(String partitionPath, String dataFileName, IOType type, boolean checkIfExists);

  /**
   * Creates a marker with early conflict detection for multi-writers. If conflict is detected,
   * an exception is thrown to fail the write operation.
   *
   * @param partitionPath  partition path in the table.
   * @param dataFileName   data file name.
   * @param type           write IO type.
   * @param checkIfExists  whether to check if the marker already exists.
   * @param config         Hudi write configs.
   * @param fileId         File ID.
   * @param activeTimeline Active timeline for the write operation.
   * @return the marker path or empty option if already exists and {@code checkIfExists} is true.
   */
  public abstract Option<Path> createWithEarlyConflictDetection(String partitionPath, String dataFileName, IOType type, boolean checkIfExists,
                                                                HoodieWriteConfig config, String fileId, HoodieActiveTimeline activeTimeline);
}
