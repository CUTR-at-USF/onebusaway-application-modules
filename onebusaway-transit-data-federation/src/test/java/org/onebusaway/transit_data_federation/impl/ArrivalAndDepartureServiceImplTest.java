/**
 * Copyright (C) 2015 University of South Florida
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.transit_data_federation.impl;

import static org.junit.Assert.assertEquals;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.block;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.blockConfiguration;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.dateAsLong;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.lsids;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.serviceIds;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.stop;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.stopTime;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.time;
import static org.onebusaway.transit_data_federation.testing.UnitTestingSupport.trip;

import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.realtime.api.TimepointPredictionRecord;
import org.onebusaway.transit_data_federation.impl.blocks.BlockStatusServiceImpl;
import org.onebusaway.transit_data_federation.impl.realtime.BlockLocationServiceImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.BlockEntryImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.StopEntryImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.TripEntryImpl;
import org.onebusaway.transit_data_federation.model.TargetTime;
import org.onebusaway.transit_data_federation.services.StopTimeService;
import org.onebusaway.transit_data_federation.services.StopTimeService.EFrequencyStopTimeBehavior;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.blocks.BlockStatusService;
import org.onebusaway.transit_data_federation.services.realtime.ArrivalAndDepartureInstance;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocation;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocationService;
import org.onebusaway.transit_data_federation.services.realtime.ScheduleDeviationSamples;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockConfigurationEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;
import org.onebusaway.transit_data_federation.services.tripplanner.StopTimeInstance;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class ArrivalAndDepartureServiceImplTest {

  private ArrivalAndDepartureServiceImpl _service;

  private BlockStatusService _blockStatusService;

  private StopTimeService _stopTimeService;

  private BlockLocationService _blockLocationService;

  @Before
  public void setup() {
    _service = new ArrivalAndDepartureServiceImpl();

    _blockStatusService = new BlockStatusServiceImpl();
    _service.setBlockStatusService(_blockStatusService);

    _stopTimeService = Mockito.mock(StopTimeServiceImpl.class);
    _service.setStopTimeService(_stopTimeService);

    _blockLocationService = Mockito.mock(BlockLocationServiceImpl.class);
    _service.setBlockLocationService(_blockLocationService);

  }

  /**
   * This method tests upstream time point predictions. Test configuration: Time
   * point predictions are upstream of the current stop_id, which means that the
   * bus didn't passed the bus stop yet. There are 2 bus stops for block and
   * both of them have the same trip_ids. In this case
   * getArrivalsAndDeparturesForStopInTimeRange should return absolute time
   * point prediction for particular stop.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange01() {
    long t = dateAsLong("2010-10-05 16:30");
    long serviceDate = dateAsLong("2010-10-05 00:00");
    int minutesBefore = 5;
    int minutesAfter = 30;

    StopEntryImpl stopA = stop("stopA", 47.0, -122.0);
    StopEntryImpl stopB = stop("stopB", 47.0, -122.0);

    // Setup block
    BlockEntryImpl block = block("blockA");
    TripEntryImpl tripA = trip("tripA", "sA", 3000);

    stopTime(0, stopA, tripA, time(16, 30), time(16, 35), 1000);
    stopTime(1, stopB, tripA, time(16, 40), time(16, 45), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), tripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);

    stopTime(2, stopA, tripA, time(16, 40), time(16, 45), 1000);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(0);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, serviceDate);
    BlockLocation blockLocationB = new BlockLocation();
    blockLocationB.setActiveTrip(bstBA.getTrip());
    blockLocationB.setBlockInstance(blockInstance);
    blockLocationB.setClosestStop(bstBA);
    blockLocationB.setDistanceAlongBlock(400);
    blockLocationB.setInService(true);
    blockLocationB.setNextStop(bstAA);
    blockLocationB.setPredicted(false);
    blockLocationB.setScheduledDistanceAlongBlock(400);

    // Set schedule deviations
    double scheduleTimes[] = {11211, 12132};
    double scheduleDeviationMus[] = {11211, 12132};
    double scheduleDeviationSigmas[] = {11211, 12132};

    ScheduleDeviationSamples sds = new ScheduleDeviationSamples(scheduleTimes,
        scheduleDeviationMus, scheduleDeviationSigmas);
    blockLocationB.setScheduleDeviations(sds);

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    tprA.setTimepointPredictedTime(1436800075);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    tprB.setTimepointPredictedTime(1436800113);
    tprB.setTripId(tripA.getId());

    blockLocationB.setTimepointPredictions(Arrays.asList(tprA, tprB));

    // Mock StopTimeInstance
    long stopTimeFrom = t - minutesBefore * 60 * 1000;
    long stopTimeTo = t + minutesAfter * 60 * 1000;

    StopTimeInstance sti1 = new StopTimeInstance(bstAB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in1 = new ArrivalAndDepartureInstance(sti1);
    in1.setBlockLocation(blockLocationB);
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime() + 5 * 60 * 1000));
    in1.setPredictedDepartureTime((long) (in1.getScheduledDepartureTime()));

    StopTimeInstance sti2 = new StopTimeInstance(bstBA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in2 = new ArrivalAndDepartureInstance(sti2);
    in2.setBlockLocation(blockLocationB);

    Date fromTimeBuffered = new Date(stopTimeFrom
        - _blockStatusService.getRunningLateWindow() * 1000);
    Date toTimeBuffered = new Date(stopTimeTo
        + _blockStatusService.getRunningEarlyWindow() * 1000);

    Mockito.when(
        _stopTimeService.getStopTimeInstancesInTimeRange(stopB,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2));

    TargetTime target = new TargetTime(t, t);
    Mockito.when(
        _blockLocationService.getScheduledLocationForBlockInstance(
            blockInstance, target.getTargetTime())).thenReturn(blockLocationB);

    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = _service.getArrivalsAndDeparturesForStopInTimeRange(
        stopB, target, stopTimeFrom, stopTimeTo);

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    assertEquals(tprB.getTimepointPredictedTime(), predictedArrivalTime);
  }

  /**
   * This method tests downstream time point predictions. Test configuration:
   * Time point predictions are downstream of the current bus, which means that
   * the bus already passed the bus stop. There are 2 bus stops for block and
   * both of them have the same trip_ids. In this case
   * getArrivalsAndDeparturesForStopInTimeRange should calculate a new
   * prediction value
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange02() {
    long t = dateAsLong("2010-10-05 16:30");
    long serviceDate = dateAsLong("2010-10-05 00:00");
    int minutesBefore = 5;
    int minutesAfter = 30;

    StopEntryImpl stopA = stop("stopA", 47.0, -122.0);
    StopEntryImpl stopB = stop("stopB", 47.0, -122.0);
    StopEntryImpl stopC = stop("stopC", 47.0, -122.0);
    StopEntryImpl stopD = stop("stopD", 47.0, -122.0);

    // Setup block
    BlockEntryImpl block = block("blockA");
    TripEntryImpl tripA = trip("tripA", "sA", 3000);

    stopTime(0, stopA, tripA, time(16, 30), time(16, 35), 1000);
    stopTime(1, stopB, tripA, time(16, 40), time(16, 45), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), tripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);

    stopTime(2, stopA, tripA, time(16, 40), time(16, 45), 1000);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(0);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, serviceDate);
    BlockLocation blockLocationB = new BlockLocation();
    blockLocationB.setActiveTrip(bstBA.getTrip());
    blockLocationB.setBlockInstance(blockInstance);
    blockLocationB.setClosestStop(bstBA);
    blockLocationB.setDistanceAlongBlock(400);
    blockLocationB.setInService(true);
    blockLocationB.setNextStop(bstAA);
    blockLocationB.setPredicted(false);
    blockLocationB.setScheduledDistanceAlongBlock(400);

    // Set schedule deviations
    double scheduleTimes[] = {11211, 12132};
    double scheduleDeviationMus[] = {11211, 12132};
    double scheduleDeviationSigmas[] = {11211, 12132};

    ScheduleDeviationSamples sds = new ScheduleDeviationSamples(scheduleTimes,
        scheduleDeviationMus, scheduleDeviationSigmas);
    blockLocationB.setScheduleDeviations(sds);

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopC.getId());
    tprA.setTimepointPredictedTime(1436800075);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopD.getId());
    tprB.setTimepointPredictedTime(1436800113);
    tprB.setTripId(tripA.getId());

    // Don't add time point predictions for stop B
    blockLocationB.setTimepointPredictions(Arrays.asList(tprA));

    // Mock StopTimeInstance
    long stopTimeFrom = t - minutesBefore * 60 * 1000;
    long stopTimeTo = t + minutesAfter * 60 * 1000;

    StopTimeInstance sti1 = new StopTimeInstance(bstAB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in1 = new ArrivalAndDepartureInstance(sti1);
    in1.setBlockLocation(blockLocationB);
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime() + 5 * 60 * 1000));
    in1.setPredictedDepartureTime((long) (in1.getScheduledDepartureTime()));

    StopTimeInstance sti2 = new StopTimeInstance(bstBA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in2 = new ArrivalAndDepartureInstance(sti2);
    in2.setBlockLocation(blockLocationB);

    Date fromTimeBuffered = new Date(stopTimeFrom
        - _blockStatusService.getRunningLateWindow() * 1000);
    Date toTimeBuffered = new Date(stopTimeTo
        + _blockStatusService.getRunningEarlyWindow() * 1000);

    Mockito.when(
        _stopTimeService.getStopTimeInstancesInTimeRange(stopB,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2));

    TargetTime target = new TargetTime(t, t);
    Mockito.when(
        _blockLocationService.getScheduledLocationForBlockInstance(
            blockInstance, target.getTargetTime())).thenReturn(blockLocationB);

    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = _service.getArrivalsAndDeparturesForStopInTimeRange(
        stopB, target, stopTimeFrom, stopTimeTo);

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());

    // Predicted arrival time shouldn't be equal to absolute time
    boolean isEqual = tprB.getTimepointPredictedTime() == predictedArrivalTime;
    assertEquals(false, isEqual);
  }

  /**
   * This method tests upstream time point predictions. Test configuration: Time
   * point predictions are upstream of the current stop_id, which means that the
   * bus didn't passed the bus stop yet. There are 2 stops for the block and
   * both of them have different trip_ids. In this case
   * getArrivalsAndDeparturesForStopInTimeRange should return absolute time
   * point prediction for particular stop.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange03() {
    long t = dateAsLong("2010-10-05 16:30");
    long serviceDate = dateAsLong("2010-10-05 00:00");
    int minutesBefore = 5;
    int minutesAfter = 30;

    StopEntryImpl stopA = stop("stopA", 47.0, -122.0);
    StopEntryImpl stopB = stop("stopB", 47.0, -122.0);

    // Setup block
    BlockEntryImpl block = block("blockA");
    TripEntryImpl tripA = trip("tripA", "sA", 3000);

    stopTime(0, stopA, tripA, time(16, 30), time(16, 35), 1000);
    stopTime(1, stopB, tripA, time(16, 40), time(16, 45), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), tripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);

    TripEntryImpl tripB = trip("tripB", "sA", 3000);
    stopTime(2, stopA, tripB, time(16, 40), time(16, 45), 1000);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(0);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, serviceDate);
    BlockLocation blockLocationB = new BlockLocation();
    blockLocationB.setActiveTrip(bstBA.getTrip());
    blockLocationB.setBlockInstance(blockInstance);
    blockLocationB.setClosestStop(bstBA);
    blockLocationB.setDistanceAlongBlock(400);
    blockLocationB.setInService(true);
    blockLocationB.setNextStop(bstAA);
    blockLocationB.setPredicted(false);
    blockLocationB.setScheduledDistanceAlongBlock(400);

    // Set schedule deviations
    double scheduleTimes[] = {11211, 12132};
    double scheduleDeviationMus[] = {11211, 12132};
    double scheduleDeviationSigmas[] = {11211, 12132};

    ScheduleDeviationSamples sds = new ScheduleDeviationSamples(scheduleTimes,
        scheduleDeviationMus, scheduleDeviationSigmas);
    blockLocationB.setScheduleDeviations(sds);

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    tprA.setTimepointPredictedTime(1436800075);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    tprB.setTimepointPredictedTime(1436800113);
    tprB.setTripId(tripB.getId());

    blockLocationB.setTimepointPredictions(Arrays.asList(tprA, tprB));

    // Mock StopTimeInstance
    long stopTimeFrom = t - minutesBefore * 60 * 1000;
    long stopTimeTo = t + minutesAfter * 60 * 1000;

    StopTimeInstance sti1 = new StopTimeInstance(bstAB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in1 = new ArrivalAndDepartureInstance(sti1);
    in1.setBlockLocation(blockLocationB);
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime() + 5 * 60 * 1000));
    in1.setPredictedDepartureTime((long) (in1.getScheduledDepartureTime()));

    StopTimeInstance sti2 = new StopTimeInstance(bstBA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in2 = new ArrivalAndDepartureInstance(sti2);
    in2.setBlockLocation(blockLocationB);

    Date fromTimeBuffered = new Date(stopTimeFrom
        - _blockStatusService.getRunningLateWindow() * 1000);
    Date toTimeBuffered = new Date(stopTimeTo
        + _blockStatusService.getRunningEarlyWindow() * 1000);

    Mockito.when(
        _stopTimeService.getStopTimeInstancesInTimeRange(stopB,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2));

    TargetTime target = new TargetTime(t, t);
    Mockito.when(
        _blockLocationService.getScheduledLocationForBlockInstance(
            blockInstance, target.getTargetTime())).thenReturn(blockLocationB);

    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = _service.getArrivalsAndDeparturesForStopInTimeRange(
        stopB, target, stopTimeFrom, stopTimeTo);

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    assertEquals(tprB.getTimepointPredictedTime(), predictedArrivalTime);
  }

  private long getPredictedArrivalTimeByStopId(
      List<ArrivalAndDepartureInstance> arrivalsAndDepartures,
      AgencyAndId stopId) {
    for (ArrivalAndDepartureInstance adi : arrivalsAndDepartures) {
      if (adi.getStop().getId().equals(stopId)) {
        return adi.getPredictedArrivalTime();
      }
    }
    return 0;
  }
}
