/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.cmd.upgrade;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;

import com.dremio.common.Version;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.dac.server.DACConfig;
import com.dremio.datastore.KVStore;
import com.dremio.datastore.KVStore.FindByRange;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.service.namespace.DatasetSplitId;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetSplit;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.google.common.collect.ImmutableList;

/**
 * Scan for datasets whose id may cause unsafe dataset split ids
 * as they are using reserved characters
 */
public class UpdateDatasetSplitIdTask extends UpgradeTask implements LegacyUpgradeTask {

  //DO NOT MODIFY
  static final String taskUUID = "d7cb2438-bc97-4c76-8a7a-ff5493e48e5e";

  public UpdateDatasetSplitIdTask() {
    super("Fix dataset split ids with invalid id",
      ImmutableList.of(ReIndexAllStores.taskUUID, "ff9f6514-d7e6-44c7-b628-865cd3ce7368"));
  }

  @Override
  public String getTaskUUID() {
    return taskUUID;
  }

  @Override
  public Version getMaxVersion() {
    return VERSION_300;
  }

  @Override
  public void upgrade(UpgradeContext context) throws Exception {
    final KVStoreProvider storeProvider = context.getKVStoreProvider();
    final KVStore<byte[], NameSpaceContainer> namespace = storeProvider.getStore(NamespaceServiceImpl.NamespaceStoreCreator.class);
    final KVStore<DatasetSplitId, DatasetSplit> splitsStore = storeProvider.getStore(NamespaceServiceImpl.DatasetSplitCreator.class);

    int fixedSplitIds = 0;
    // namespace#find() returns entries ordered by depth, so sources will
    // be processed before folders, which will be processed before datasets
    for(Map.Entry<byte[], NameSpaceContainer> entry: namespace.find()) {
      final NameSpaceContainer container = entry.getValue();

      if (container.getType() != NameSpaceContainer.Type.DATASET) {
        continue;
      }

      DatasetConfig config = entry.getValue().getDataset();
      if (config.getType() == DatasetType.VIRTUAL_DATASET) {
        continue;
      }

      if (config.getReadDefinition() == null || config.getReadDefinition().getSplitVersion() == null) {
        continue;
      }

      if (!DatasetSplitId.mayRequireNewDatasetId(config)) {
        // Datasets which do not contain reserved characters are fine
        continue;
      }

      fixSplits(splitsStore, config);
    }

    System.out.printf("  Updated %d dataset splits with new ids.%n", fixedSplitIds);
  }

  private void fixSplits(final KVStore<DatasetSplitId, DatasetSplit> splitsStore,
      DatasetConfig config) {
    final long version = config.getReadDefinition().getSplitVersion();

    // Get old splits
    final FindByRange<DatasetSplitId> query = DatasetSplitId.unsafeGetSplitsRange(config);
    for (Entry<DatasetSplitId, DatasetSplit> entry : splitsStore.find(query)) {
      final DatasetSplitId oldId = entry.getKey();
      final DatasetSplit split = entry.getValue();

      // Generate new Id and compare with old id
      final DatasetSplitId newId = DatasetSplitId.of(config, split, version);
      if (oldId.equals(newId)) {
        continue;
      }

      // Delete the previous entry and add a new one
      splitsStore.delete(oldId);
      splitsStore.put(newId, split.setVersion(null));
    }
  }


  /**
   * Run the task against a directory
   * @param args one single argument, the path to the database
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Require one argument: path to the database");
    }

    final String dbPath = args[0];

    if (!Files.isDirectory(Paths.get(dbPath))) {
      System.out.println("No database found. Skipping splits check");
      return;
    }

    final SabotConfig sabotConfig = DACConfig.newConfig().getConfig().getSabotConfig();
    final ScanResult classpathScan = ClassPathScanner.fromPrescan(sabotConfig);
    try (final KVStoreProvider storeProvider = new LocalKVStoreProvider(classpathScan, args[0], false, true)) {
      storeProvider.start();

      final UpgradeContext context = new UpgradeContext(storeProvider, null, null);
      final UpdateDatasetSplitIdTask task = new UpdateDatasetSplitIdTask();
      task.upgrade(context);
    }
  }

  @Override
  public String toString() {
    return String.format("'%s' up to %s)", getDescription(), getMaxVersion());
  }
}
