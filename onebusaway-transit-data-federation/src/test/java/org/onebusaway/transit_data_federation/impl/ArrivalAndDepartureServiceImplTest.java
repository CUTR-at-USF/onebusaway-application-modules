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
import org.onebusaway.realtime.api.VehicleLocationRecord;
import org.onebusaway.transit_data_federation.impl.blocks.BlockStatusServiceImpl;
import org.onebusaway.transit_data_federation.impl.blocks.ScheduledBlockLocationServiceImpl;
import org.onebusaway.transit_data_federation.impl.realtime.BlockLocationServiceImpl;
import org.onebusaway.transit_data_federation.impl.realtime.VehicleLocationRecordCacheImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.BlockEntryImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.StopEntryImpl;
import org.onebusaway.transit_data_federation.impl.transit_graph.TripEntryImpl;
import org.onebusaway.transit_data_federation.model.TargetTime;
import org.onebusaway.transit_data_federation.services.StopTimeService;
import org.onebusaway.transit_data_federation.services.StopTimeService.EFrequencyStopTimeBehavior;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.blocks.BlockStatusService;
import org.onebusaway.transit_data_federation.services.blocks.ScheduledBlockLocation;
import org.onebusaway.transit_data_federation.services.realtime.ArrivalAndDepartureInstance;
import org.onebusaway.transit_data_federation.services.realtime.BlockLocation;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockConfigurationEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopTimeEntry;
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

  private BlockLocationServiceImpl _blockLocationService;

  // Setup current time
  private long currentTime = dateAsLong("2015-07-23 13:00");

  private long serviceDate = dateAsLong("2015-07-23 00:00");

  // Stops
  private StopEntryImpl stopA = stop("stopA", 47.0, -122.0);

  private StopEntryImpl stopB = stop("stopB", 47.0, -128.0);

  private TripEntryImpl tripA = trip("tripA", "sA", 3000);

  @Before
  public void setup() {
    _service = new ArrivalAndDepartureServiceImpl();

    _blockStatusService = new BlockStatusServiceImpl();
    _service.setBlockStatusService(_blockStatusService);

    _stopTimeService = Mockito.mock(StopTimeServiceImpl.class);
    _service.setStopTimeService(_stopTimeService);

    _blockLocationService = new BlockLocationServiceImpl();
    _blockLocationService.setLocationInterpolation(false);
    _service.setBlockLocationService(_blockLocationService);
  }

  /**
   * This method tests upstream time point predictions. Test configuration: Time
   * point predictions are upstream of the current stop_id, which means that the
   * bus didn't passed the bus stop yet. There are 2 bus stops which have the
   * real time arrival times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange should return absolute time
   * point prediction for particular stop.
   * 
   * ***********TimePointPredictions*************
   * *********** 13:00 (Bus is here)  ***********
   * ***********         13:20        ***********
   * ***********     13:30 (Stop A)   ***********
   * ***********         13:40        ***********
   * ***********     13:50 (Stop B)   *********** <-- We are looking for here
   * ***********         13:60        ***********
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange01() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = serviceDate + time(13, 30);
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    long tprBTime = serviceDate + time(13, 50);
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    // Check if the predictedArrivalTime is exactly the same with TimepointPrediction
    assertEquals(tprB.getTimepointPredictedArrivalTime() / 1000,
        predictedArrivalTime / 1000);
  }
  
  /**
   * This method tests upstream time point predictions. Test configuration: Time
   * point predictions are upstream of the current stop_id, which means that the
   * bus didn't passed the bus stop yet. There is only 1 bus stop which has the
   * real time arrival times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange should calculate a new arrival time 
   * for Stop B and it should be different than the scheduled arrival time.
   * 
   * ***********TimePointPredictions*******ScheduledStopTimes*****
   * *********** 13:00 (Bus is here)  ******      13:00      *****
   * ***********         13:20        ******      13:20      *****
   * ***********         13:30        ******   13:30(Stop A) *****
   * ***********     13:35 (Stop A)   ******      13:35      *****
   * ***********         13:40        ******   13:40(Stop B) *****
   * ***********         13:50        ******      13:45      ***** <-- We should get the new prediction
   * ***********         13:60        ******      13:50      *****
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange02() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = serviceDate + time(13, 35);
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long scheduledArrivalTime = getScheduledStopTimeByStopId(tripA, stopB.getId());
    // Check if the predictedArrivalTime is no same with the scheduledArrivalTime
    assertEquals(false, predictedArrivalTime == scheduledArrivalTime);
  }
  
  /**
   * This method tests downstream time point predictions. Test configuration: Time
   * point predictions are downstream of the current stop_id, which means that the
   * bus already passed the bus stop. There are 2 bus stops which have the
   * real time arrival times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange should return absolute time
   * point prediction for particular stop.
   * 
   * ***********TimePointPredictions*************
   * ***********         13:00        ***********
   * ***********         13:20        ***********
   * ***********     13:30 (Stop A)   ***********
   * ***********         13:40        ***********
   * ***********     13:50 (Stop B)   *********** <-- We are looking for here
   * *********** 14:00 (Bus is here)  ***********
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange03() {
    // Override the current time with a later time than the time point predictions
    currentTime = dateAsLong("2015-07-23 14:00");
    
    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = serviceDate + time(13, 30);
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    long tprBTime = serviceDate + time(13, 50);
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    // Check if the predictedArrivalTime is exactly the same with TimepointPrediction
    assertEquals(tprB.getTimepointPredictedArrivalTime() / 1000,
        predictedArrivalTime / 1000);
  }
  
  public List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(
      List<TimepointPredictionRecord> timepointPredictions) {
    TargetTime target = new TargetTime(currentTime, currentTime);

    // Setup block
    BlockEntryImpl block = block("blockA");

    stopTime(0, stopA, tripA, time(13, 30), time(13, 35), 1000);
    stopTime(1, stopB, tripA, time(13, 40), time(13, 45), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), tripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);

    stopTime(2, stopA, tripA, time(13, 50), time(13, 55), 1000);
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

    blockLocationB.setTimepointPredictions(timepointPredictions);

    // Mock StopTimeInstance with time frame
    long stopTimeFrom = dateAsLong("2015-07-23 00:00");
    long stopTimeTo = dateAsLong("2015-07-24 00:00");

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

    // Create and add vehicle location record cache
    VehicleLocationRecordCacheImpl _cache = new VehicleLocationRecordCacheImpl();
    VehicleLocationRecord vlr = new VehicleLocationRecord();
    vlr.setBlockId(blockLocationB.getBlockInstance().getBlock().getBlock().getId());
    vlr.setTripId(tripA.getId());
    vlr.setTimepointPredictions(blockLocationB.getTimepointPredictions());
    vlr.setTimeOfRecord(currentTime);
    vlr.setVehicleId(new AgencyAndId("1", "123"));

    // Create ScheduledBlockLocation for cache
    ScheduledBlockLocation sbl = new ScheduledBlockLocation();
    sbl.setActiveTrip(blockLocationB.getActiveTrip());
    sbl.setClosestStop(bstAA);
    sbl.setNextStop(bstAB);

    // Add data to cache
    _cache.addRecord(blockInstance, vlr, sbl, null);
    _blockLocationService.setVehicleLocationRecordCache(_cache);
    ScheduledBlockLocationServiceImpl scheduledBlockLocationServiceImpl = new ScheduledBlockLocationServiceImpl();
    _blockLocationService.setScheduledBlockLocationService(scheduledBlockLocationServiceImpl);

    // Call ArrivalAndDepartureService
    return _service.getArrivalsAndDeparturesForStopInTimeRange(stopB, target,
        stopTimeFrom, stopTimeTo);
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
  
  private long getScheduledStopTimeByStopId(TripEntryImpl trip, AgencyAndId id) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id)) {
        return ste.getArrivalTime() + serviceDate;
      }
    }
    return 0;
  }
}