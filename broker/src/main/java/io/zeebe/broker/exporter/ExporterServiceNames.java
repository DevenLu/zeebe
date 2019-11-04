/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.exporter;

import io.zeebe.servicecontainer.ServiceName;

public class ExporterServiceNames {

  public static ServiceName<ExporterDirectorService> exporterDirectorServiceName(int partitionId) {
    final String name = String.format("io.zeebe.broker.exporter.%d", partitionId);
    return ServiceName.newServiceName(name, ExporterDirectorService.class);
  }
}
