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
package com.dremio.exec.catalog;

import java.util.Collection;

import org.apache.calcite.schema.Function;

import com.dremio.exec.store.ischema.tables.TablesTable;
import com.dremio.service.namespace.NamespaceKey;

/**
 * Simplified catalog object needed for use with DremioCatalogReader. A simplified version of Catalog.
 */
public interface SimpleCatalog<T extends SimpleCatalog<T>> {

  /**
   * Retrieve a table, first checking the default schema.
   *
   * @param key
   * @return
   */
  DremioTable getTable(NamespaceKey key);

  /**
   * Get a list of all schemas.
   *
   * @param path
   *          The path to contextualize to. If the path has no fields, get all schemas. Note
   *          that this does include nested schemas.
   * @return Iterable list of strings of each schema.
   */
  Iterable<String> listSchemas(NamespaceKey path);

  /**
   * Get a list of all tables available in this catalog within the provided path.
   * @param path The path to constraint the listing to.
   * @return The set of tables within the provided path.
   */
  Iterable<TablesTable.Table> listDatasets(NamespaceKey path);

  /**
   * Get a list of functions. Provided specifically for DremioCatalogReader.
   * @param path
   * @return
   */
  Collection<Function> getFunctions(NamespaceKey path);

  /**
   * Get the default schema for this catalog. Returns null if there is no default.
   * @return The default schema path.
   */
  NamespaceKey getDefaultSchema();

  /**
   * Return a new Catalog contextualized to the provided username and default schema
   *
   * @param username
   * @param newDefaultSchema
   * @return
   */
  T resolveCatalog(String username, NamespaceKey newDefaultSchema);

  /**
   * Return a new Catalog contextualized to the provided default schema
   * @param newDefaultSchema
   * @return A new schema with the same user but with the newly provided default schema.
   */
  T resolveCatalog(NamespaceKey newDefaultSchema);
}
