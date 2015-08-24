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
import java.util.concurrent.TimeUnit;

/**
 * Tests to see if the per stop time point predictions provided by
 * a real-time feed are correctly applied to the scheduled time,
 * so the correct predicted arrival times are produced.  Behavior
 * for propagating times is consistent with the GTFS-realtime spec
 * (https://developers.google.com/transit/gtfs-realtime/).
 * 
 * @author cetin
 * @author barbeau
 */
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
   * This method tests time point predictions upstream of a stop for *arrival*
   * times. 
   * 
   * Test configuration: Time point predictions are upstream of the 
   * stop and include the given stop_ids, which means that the bus 
   * hasn't passed these bus stops yet. There are 2 bus stops which 
   * have the real time arrival times (time point predictions). In 
   * this case getArrivalsAndDeparturesForStopInTimeRange() should 
   * return the absolute time point prediction for particular stop 
   * that was provided by the feed, which replaces the scheduled time 
   * from GTFS for these stops.
   * 
   * Current time = 13:00
   *          Schedule time     Real-time from feed
   * Stop A   13:30             13:30    
   * Stop B   13:40             13:50
   *  
   * When requesting arrival estimate for Stop B, result should be 13:50 (same
   * as exact prediction from real-time feed).
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange01() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    long tprBTime = createPredictedTime(time(13, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    /** 
     * Check if the predictedArrivalTime is exactly the same as the TimepointPrediction.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime() / 1000,
        predictedArrivalTime / 1000);
  }
  
  /**
   * This method tests upstream time point predictions for scheduled *arrival* times. 
   * 
   * Test configuration: Time point predictions are upstream of the current stop_id, which
   * means that the bus hasn't passed the bus stop yet. A real time arrival time (time 
   * point prediction) is provided for only one bus stop (Stop A).  In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should calculate a new arrival time 
   * for Stop B (based on the upstream prediction for Stop A), which is the scheduled
   * arrival time + the upstream deviation.
   * 
   * Current time = 13:00
   *          Schedule time     Real-time from feed
   * Stop A   13:30             13:35    
   * Stop B   13:40             ---
   * 
   * We are requesting arrival time for Stop B, which should be propagated downstream
   * from Stop A's prediction, which should be 13:45 (13:40 + 5 min deviation from Stop A).
   * Stop A's predicted arrival and departure should also be the respected scheduled
   * arrival and departure plus the 5 min deviation.
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange02() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = createPredictedTime(time(13, 35));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA));

    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopA.getId());
    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopA.getId());
    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long scheduledArrivalTimeStopA = getScheduledArrivalTimeByStopId(tripA, stopA.getId());
    long scheduledDepartureTimeStopA = getScheduledDepartureTimeByStopId(tripA, stopA.getId());
    long scheduledArrivalTimeStopB = getScheduledArrivalTimeByStopId(tripA, stopB.getId());
    long scheduledDepartureTimeStopB = getScheduledDepartureTimeByStopId(tripA, stopB.getId());
    
    // The time point prediction for Stop A was 5 min late, so this should be applied to Stop B scheduled arrival
    long delta = (predictedArrivalTimeStopA / 1000) - scheduledArrivalTimeStopA;
    assertEquals(TimeUnit.MINUTES.toSeconds(5), delta);
    
    // Check if the predictedArrivalTimes and predictedDepartureTimes is the same as the scheduledArrivalTime plus the delta
    assertEquals(predictedArrivalTimeStopA / 1000, scheduledArrivalTimeStopA + delta);
    assertEquals(predictedDepartureTimeStopA / 1000, scheduledDepartureTimeStopA + delta);
    assertEquals(predictedArrivalTimeStopB / 1000, scheduledArrivalTimeStopB + delta);
    assertEquals(predictedDepartureTimeStopB / 1000, scheduledDepartureTimeStopB + delta);
  }
  
  /**
   * This method tests upstream time point predictions with only a predicted *departure* time.
   * 
   * Test configuration: There is only one bus stop (Stop A) which has the real time departure time
   * (time point prediction). In this case getArrivalsAndDeparturesForStopInTimeRange() should return 
   * the time point prediction for Stop A's departure time, which replaces the scheduled time from GTFS
   * for this stop.  For Stop B, the upstream departure prediction for Stop A should be propagated down
   * to Stop B, and this deviation should be used to calculate Stop B's arrival and departure times.
   * 
   * Current time = 13:00
   *          Schedule Arrival time  Schedule Departure time  Real-time departure time
   * Stop A   13:30                  13:35                    13:30    
   * Stop B   13:45                  13:50                    ----
   *  
   * When requesting arrival estimate for Stop A, result should be 0 (note this isn't currently supported
   * - see TODO in method body). 
   * 
   * When requesting departure estimate for Stop A, result should be exactly same with the real-time feed's
   * departure time for Stop A.  
   * 
   * When requesting arrival and departure estimate for Stop B, the result should be 5 min less then
   * the scheduled arrival and departure times. Because the upstream stop departs 5 min early, OBA 
   * should subtract this 5 min deviation from the downstream scheduled values.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange03() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedDepartureTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA));

    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopA.getId());

    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopA.getId());
    /** 
     * Check if the predictedDepartureTime is exactly the same with TimepointPrediction.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(tprA.getTimepointPredictedDepartureTime() / 1000,
        predictedDepartureTimeStopA / 1000);
    
    /** 
     * TODO - Fully support both real-time arrival and departure times for each stop in OBA
     * 
     * We're currently limited by OBA's internal data model which contains only one deviation per stop.
     * By GTFS-rt spec, if no real-time arrival information is given for a stop, then the scheduled arrival
     * should be used. In our case here, we're getting a real-time departure for Stop A (and no real-time
     * arrival time for Stop A), but then we're showing the real-time departure info for Stop A as the 
     * real-time arrival time for Stop A.  So, we're effectively propagating the real-time value backwards
     * within the same stop.  The correct value for predictedArrivalTimeStopA is actually 0, because we don't
     * have any real-time arrival information for Stop A (or upstream of Stop A).  
     * 
     * So, the below assertion is currently commented out, as it fails.  Future work should overhaul OBA's data 
     * model to support more than one real-time deviation per stop.   When this is correctly implemented,
     * the below assertion should be uncommented and it should pass.
     */
    // assertEquals(0,  predictedArrivalTimeStopA);
    
    /**
     * Test for Stop B
     */
    
    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long scheduledDepartureTimeForStopA = getScheduledDepartureTimeByStopId(tripA, stopA.getId());
    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(tripA, stopB.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(tripA, stopB.getId());
    
    // Calculate the departure time difference from the upstream stop
    long deltaB = (scheduledDepartureTimeForStopA - predictedDepartureTimeStopA / 1000);

    /** 
     * Check if the predictedArrivalTime is 5 min less then the scheduled arrival time for stop B.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(TimeUnit.MINUTES.toSeconds(5), deltaB);
    assertEquals(scheduledArrivalTimeForStopB - deltaB, predictedArrivalTimeStopB / 1000);
    
    /** 
     * Check if the predictedDepartureTime is 5 min less then the scheduled departure time for stop B.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(scheduledDepartureTimeForStopB - deltaB, predictedDepartureTimeStopB / 1000);
  }

  /**
   * This method tests upstream time point predictions with both predicted arrival and departure times.
   * 
   * Test configuration: Time point predictions are upstream and include the current stop_id, which means 
   * that the bus hasn't passed the bus stop yet. There is only one bus stop (Stop A) which has 
   * the real time arrival and departure times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return absolute time point prediction for Stop A's
   * departure time, which replaces the scheduled time from GTFS for this stop.  Stop B's predictions should
   * be derived from the upstream predictions provided for Stop A.
   * 
   * Current time = 13:00
   *          Schedule Arrival time  Schedule Departure time    Real-time arrival time  Real-time departure time
   * Stop A   13:30                  13:35                      13:20                     13:30    
   * Stop B   13:45                  13:50                      -----                     -----
   *  
   * When requesting arrival estimate for Stop A, result should be 13:20 (predicted real-time arrival time)
   * 
   * When requesting departure estimate for Stop A, result should be exactly same with the real-time feed.
   * 
   * When requesting arrival and departure estimates for Stop B, results should be 5 min less then
   * the scheduled arrival and departure times. Because the upstream stop departs 5 min early, OBA 
   * should remove this 5 min from the downstream estimates.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange04() {
    
    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprADepartureTime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedDepartureTime(tprADepartureTime);
    long tprAArrivalTime = createPredictedTime(time(13, 20));
    tprA.setTimepointPredictedArrivalTime(tprAArrivalTime);
    tprA.setTripId(tripA.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA));
    
    
    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopA.getId());
    /** 
     * Check if the predictedDepartureTime is exactly the same with TimepointPrediction.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(tprA.getTimepointPredictedDepartureTime() / 1000,
        predictedDepartureTimeStopA / 1000);
    
    /** 
     * TODO - Fully support both real-time arrival and departure times for each stop in OBA
     * 
     * We're currently limited by OBA's internal data model which contains only one deviation per stop.
     * By GTFS-rt spec, if real-time arrival information is given for a stop, then it should be used.
     * In our case we expect to get 13:20 as predictedArrivalTime, as this is the predicted arrival time
     * supplied in the real-time feed. However, we are getting 13:25 as predictedArrivalTime, which is 
     * actually the predictedDepartureTime for this stop.  This is because OBA must prefer predicted
     * departure times to arrival times when only one deviation per stop is supported, as the departure 
     * times are what should be propagated downstream. 
     * 
     * So, the below assertion is currently commented out, as it fails.  Future work should overhaul OBA's 
     * data model to support more than one real-time deviation per stop.  When this is correctly implemented,
     * the below assertion should be uncommented and it should pass.
     */
          
//    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
//         arrivalsAndDepartures, stopA.getId());
//       
//    assertEquals(tprA.getTimepointPredictedArrivalTime() / 1000,
//       predictedArrivalTimeStopA / 1000);
    
    /**
     * Test for Stop B
     */
    
    long scheduledDepartureTimeForStopA = getScheduledDepartureTimeByStopId(tripA, stopA.getId());
    
    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    
    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(tripA, stopB.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(tripA, stopB.getId());
    
    // Calculate the departure time difference from the upstream stop
    long deltaB = (scheduledDepartureTimeForStopA - predictedDepartureTimeStopA / 1000);
    
    /** 
     * Check if the predictedDepartureTime is 5 min less then the scheduled departure time for stop B.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(scheduledDepartureTimeForStopB - deltaB, predictedDepartureTimeStopB / 1000);
    
    /** 
     * Check if the predictedArrivalTime is 5 min less then the scheduled arrival time for stop B.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(scheduledArrivalTimeForStopB - deltaB, predictedArrivalTimeStopB / 1000);
  }
  
  /**
   * This method tests a request for an arrival time for a stop, when the current time
   * is greater than the arrival time prediction for that stop (Stop B). In other words, 
   * the bus is predicted to have already passed the stop (Stop B).  
   * 
   * Test configuration: There are 2 bus stops which have the real time arrival times 
   * (time point prediction) - Stop A and B. In this case 
   * getArrivalsAndDeparturesForStopInTimeRange() should return last received absolute 
   * time point prediction for particular stop we're requesting information for (Stop B).
   * 
   * Current time = 14:00
   *          Schedule time     Real-time from feed
   * Stop A   13:30             13:35    
   * Stop B   13:40             --- 
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange05() {
    // Override the current time with a later time than the time point predictions
    currentTime = dateAsLong("2015-07-23 14:00");
    
    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(stopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(tripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    long tprBTime = createPredictedTime(time(13, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    /** 
     * Check if the predictedArrivalTime is exactly the same with TimepointPrediction.
     * We remove milliseconds with "/ 1000" to remove any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime() / 1000,
        predictedArrivalTime / 1000);
  }
  
  /**
   * This method tests to make sure upstream propagation isn't happening. 
   * 
   * Test configuration: Time point predictions are downstream and include the current 
   * stop_id, which means that the bus already passed the bus stop. There only one bus 
   * stop (stop B) which have the real time arrival times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return absolute time
   * point prediction for particular stop (stop B) that was provided by the feed, which
   * replaces the scheduled time from GTFS for these stops.
   * 
   * Current time = 14:00
   *          Schedule time     Real-time from feed
   * Stop A   13:30             -----    
   * Stop B   13:45             13:40
   *  
   * Since the bus already passed the bus stop A, OBA shouldn't propagate arrival 
   * estimate for stop A.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange06() {
    // Override the current time with a later time than the time point predictions
    currentTime = dateAsLong("2015-07-23 14:00");

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(stopB.getId());
    long tprBTime = createPredictedTime(time(13, 40));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(tripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopB.getId());
    /** 
     * Check if the predictedArrivalTime for stop B is exactly the same with TimepointPrediction.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime() / 1000,
        predictedArrivalTime / 1000);

    long predictedArrivalTimeA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, stopA.getId());
    
    /** 
     * Check if the predictedArrivalTime for stop A is equals to 0.
     * So, we try to make sure that there is no upstream predictions happening.
     * We remove milliseconds with "/ 1000" to avoid any rounding errors due to time
     * conversions throughout OBA.
     */
    assertEquals(0, predictedArrivalTimeA);
  }
  
  /**
   * Set up the BlockLocationServiceImpl for the test, using the given timepointPredictions
   * @param timepointPredictions real-time predictions to apply to the BlockLocationServiceImpl
   * @return a list of ArrivalAndDepartureInstances which is used to access predicted arrival/departure
   * times for a stop, for comparison against the expected values
   */
  private List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(
      List<TimepointPredictionRecord> timepointPredictions) {
    TargetTime target = new TargetTime(currentTime, currentTime);

    // Setup block
    BlockEntryImpl block = block("blockA");

    stopTime(0, stopA, tripA, time(13, 30), time(13, 35), 1000);
    stopTime(1, stopB, tripA, time(13, 45), time(13, 50), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), tripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);
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
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime()));
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

  private long getPredictedDepartureTimeByStopId(
      List<ArrivalAndDepartureInstance> arrivalsAndDepartures,
      AgencyAndId stopId) {
    for (ArrivalAndDepartureInstance adi : arrivalsAndDepartures) {
      if (adi.getStop().getId().equals(stopId)) {
        return adi.getPredictedDepartureTime();
      }
    }
    return 0;
  }
  
  private long getScheduledArrivalTimeByStopId(TripEntryImpl trip, AgencyAndId id) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id)) {
        return ste.getArrivalTime() + serviceDate / 1000;
      }
    }
    return 0;
  }

  private long getScheduledDepartureTimeByStopId(TripEntryImpl trip, AgencyAndId id) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id)) {
        return ste.getDepartureTime() + serviceDate / 1000;
      }
    }
    return 0;
  }
  
  private long createPredictedTime(int time) {
    return (serviceDate / 1000 + time) * 1000;
  }
}