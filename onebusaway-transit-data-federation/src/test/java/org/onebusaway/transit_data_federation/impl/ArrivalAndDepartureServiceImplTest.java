/**
 * Copyright (C) 2016 University of South Florida
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
 * Tests to see if the per stop time point predictions provided by a real-time
 * feed are correctly applied to the scheduled time, so the correct predicted
 * arrival times are produced. Behavior for propagating times is consistent with
 * the GTFS-realtime spec
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
  private long mCurrentTime = dateAsLong("2015-07-23 13:00");

  private long mServiceDate = dateAsLong("2015-07-23 00:00");

  // Stops
  private StopEntryImpl mStopA = stop("stopA", 47.0, -122.0);

  private StopEntryImpl mStopB = stop("stopB", 47.0, -128.0);

  private TripEntryImpl mTripA = trip("tripA", "sA", 3000);
  
  private TripEntryImpl mTripB = trip("tripB", "sB", 3000);
  
  private TripEntryImpl mTripC = trip("tripC", "sC", 3000);

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
   * Test configuration: Time point predictions are upstream of the stop and
   * include the given stop_ids, which means that the bus hasn't passed these
   * bus stops yet. There are 2 bus stops which have the real time arrival times
   * (time point predictions). In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return the absolute
   * time point prediction for particular stop that was provided by the feed,
   * which replaces the scheduled time from GTFS for these stops.
   * 
   * Current time = 13:00 
   *        Schedule time    Real-time from feed 
   * Stop A 13:30            13:30
   * Stop B 13:40            13:50
   * 
   * When requesting arrival estimate for Stop B, result should be 13:50 (same
   * as exact prediction from real-time feed).
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange01() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(13, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTime = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    /**
     * Check if the predictedArrivalTime is exactly the same as the
     * TimepointPrediction.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime(), predictedArrivalTime);
  }

  /**
   * This method tests upstream time point predictions for scheduled *arrival*
   * times.
   * 
   * Test configuration: Time point predictions are upstream of the current
   * stop_id, which means that the bus hasn't passed the bus stop yet. A real
   * time arrival time (time point prediction) is provided for only one bus stop
   * (Stop A). In this case getArrivalsAndDeparturesForStopInTimeRange() should
   * calculate a new arrival time for Stop B (based on the upstream prediction
   * for Stop A), which is the scheduled arrival time + the upstream deviation.
   * 
   * Current time = 13:00 
   *          Schedule time    Real-time from feed
   * Stop A   13:30            13:35
   * Stop B   13:40            ---
   * 
   * We are requesting arrival time for Stop B, which should be propagated
   * downstream from Stop A's prediction, which should be 13:45 (13:40 + 5 min
   * deviation from Stop A). Stop A's predicted arrival and departure should
   * also be the respective scheduled arrival and departure plus the 5 min
   * deviation.
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange02() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 35));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprA));

    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long scheduledArrivalTimeStopA = getScheduledArrivalTimeByStopId(mTripA,
        mStopA.getId());
    long scheduledDepartureTimeStopA = getScheduledDepartureTimeByStopId(
        mTripA, mStopA.getId());
    long scheduledArrivalTimeStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledDepartureTimeStopB = getScheduledDepartureTimeByStopId(
        mTripA, mStopB.getId());

    // The time point prediction for Stop A was 5 min late, so this should be
    // applied to Stop B scheduled arrival
    long delta = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopA)
        - scheduledArrivalTimeStopA;
    assertEquals(TimeUnit.MINUTES.toSeconds(5), delta);

    // Check if the predictedArrivalTimes and predictedDepartureTimes is the
    // same as the scheduledArrivalTime plus the delta
    assertEquals(TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopA),
        scheduledArrivalTimeStopA + delta);
    assertEquals(TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopA),
        scheduledDepartureTimeStopA + delta);
    assertEquals(TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB),
        scheduledArrivalTimeStopB + delta);
    assertEquals(TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopB),
        scheduledDepartureTimeStopB + delta);
  }

  /**
   * This method tests upstream time point predictions with only a predicted
   * *departure* time.
   * 
   * Test configuration: There is only one bus stop (Stop A) which has the real
   * time departure time (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return the time point
   * prediction for Stop A's departure time, which replaces the scheduled time
   * from GTFS for this stop. For Stop B, the upstream departure prediction for
   * Stop A should be propagated down to Stop B, and this deviation should be
   * used to calculate Stop B's arrival and departure times.
   * 
   * Current time = 13:00 
   *          Schedule Arrival time    Schedule Departure time    Real-time departure time
   * Stop A   13:30                    13:35                      13:30
   * Stop B   13:45                    13:50                      ----
   * 
   * When requesting arrival estimate for Stop A, result should be 0 (note this
   * isn't currently supported - see TODO in method body).
   * 
   * When requesting departure estimate for Stop A, result should be exactly
   * same with the real-time feed's departure time for Stop A.
   * 
   * When requesting arrival and departure estimate for Stop B, the result
   * should be 5 min less then the scheduled arrival and departure times.
   * Because the upstream stop departs 5 min early, OBA should subtract this 5
   * min deviation from the downstream scheduled values.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange03() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedDepartureTime(tprATime);
    tprA.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprA));

    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());

    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    /**
     * Check if the predictedDepartureTime is exactly the same with
     * TimepointPrediction.
     */
    assertEquals(tprA.getTimepointPredictedDepartureTime(),
        predictedDepartureTimeStopA);

    /**
     * TODO - Fully support both real-time arrival and departure times for each
     * stop in OBA
     * 
     * We're currently limited by OBA's internal data model which contains only
     * one deviation per stop. By GTFS-rt spec, if no real-time arrival
     * information is given for a stop, then the scheduled arrival should be
     * used. In our case here, we're getting a real-time departure for Stop A
     * (and no real-time arrival time for Stop A), but then we're showing the
     * real-time departure info for Stop A as the real-time arrival time for
     * Stop A. So, we're effectively propagating the real-time value backwards
     * within the same stop. The correct value for predictedArrivalTimeStopA is
     * actually 0, because we don't have any real-time arrival information for
     * Stop A (or upstream of Stop A).
     * 
     * So, the below assertion is currently commented out, as it fails. Future
     * work should overhaul OBA's data model to support more than one real-time
     * deviation per stop. When this is correctly implemented, the below
     * assertion should be uncommented and it should pass.
     */
    // assertEquals(0, predictedArrivalTimeStopA);

    /**
     * Test for Stop B
     */

    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long scheduledDepartureTimeForStopA = getScheduledDepartureTimeByStopId(
        mTripA, mStopA.getId());
    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(
        mTripA, mStopB.getId());

    // Calculate the departure time difference from the upstream stop
    long deltaB = (scheduledDepartureTimeForStopA - TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopA));

    /**
     * Check if the predictedArrivalTime is 5 min less then the scheduled
     * arrival time for stop B.
     */
    assertEquals(TimeUnit.MINUTES.toSeconds(5), deltaB);
    assertEquals(scheduledArrivalTimeForStopB - deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB));

    /**
     * Check if the predictedDepartureTime is 5 min less then the scheduled
     * departure time for stop B.
     */
    assertEquals(scheduledDepartureTimeForStopB - deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopB));
  }

  /**
   * This method tests upstream time point predictions with both predicted
   * arrival and departure times.
   * 
   * Test configuration: Time point predictions are upstream and include the
   * current stop_id, which means that the bus hasn't passed the bus stop yet.
   * There is only one bus stop (Stop A) which has the real time arrival and
   * departure times (time point prediction). In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return absolute time
   * point prediction for Stop A's departure time, which replaces the scheduled
   * time from GTFS for this stop. Stop B's predictions should be derived from
   * the upstream predictions provided for Stop A.
   * 
   * Current time = 13:00 
   *          Schedule Arrival time    Schedule Departure time    Real-time arrival time    Real-time departure time
   * Stop A   13:30                    13:35                      13:20                     13:30
   * Stop B   13:45                    13:50                      -----                     -----
   * 
   * When requesting arrival estimate for Stop A, result should be 13:20
   * (predicted real-time arrival time). Note that this currently isn't support
   * - see TODO statement in method body.
   * 
   * When requesting departure estimate for Stop A, result should be 13:30
   * (predicted real-time departure time).
   * 
   * When requesting arrival and departure estimates for Stop B, results should
   * be 5 min less then the scheduled arrival and departure times. Because the
   * upstream Stop A departs 5 min early, OBA should subtract this 5 min from
   * the downstream estimates.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange04() {

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprADepartureTime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedDepartureTime(tprADepartureTime);
    long tprAArrivalTime = createPredictedTime(time(13, 20));
    tprA.setTimepointPredictedArrivalTime(tprAArrivalTime);
    tprA.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprA));

    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    /**
     * Check if the predictedDepartureTime is exactly the same with
     * TimepointPrediction.
     */
    assertEquals(tprA.getTimepointPredictedDepartureTime(),
        predictedDepartureTimeStopA);

    /**
     * TODO - Fully support both real-time arrival and departure times for each
     * stop in OBA
     * 
     * We're currently limited by OBA's internal data model which contains only
     * one deviation per stop. By GTFS-rt spec, if real-time arrival information
     * is given for a stop, then it should be used. In our case we expect to get
     * 13:20 as predictedArrivalTime, as this is the predicted arrival time
     * supplied in the real-time feed. However, we are getting 13:25 as
     * predictedArrivalTime, which is actually the predictedDepartureTime for
     * this stop. This is because OBA must prefer predicted departure times to
     * arrival times when only one deviation per stop is supported, as the
     * departure times are what should be propagated downstream.
     * 
     * So, the below assertion is currently commented out, as it fails. Future
     * work should overhaul OBA's data model to support more than one real-time
     * deviation per stop. When this is correctly implemented, the below
     * assertion should be uncommented and it should pass.
     */

    // long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
    // arrivalsAndDepartures, stopA.getId());
    //
    // assertEquals(TimeUnit.MILLISECONDS.toSeconds(tprA.getTimepointPredictedArrivalTime()),
    // TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopA));

    /**
     * Test for Stop B
     */

    long scheduledDepartureTimeForStopA = getScheduledDepartureTimeByStopId(
        mTripA, mStopA.getId());

    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(
        mTripA, mStopB.getId());

    // Calculate the departure time difference from the upstream stop
    long deltaB = scheduledDepartureTimeForStopA
        - TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopA);

    /**
     * Check if the predictedDepartureTime is 5 min less then the scheduled
     * departure time for stop B.
     */
    assertEquals(TimeUnit.MINUTES.toSeconds(5), deltaB);
    assertEquals(scheduledDepartureTimeForStopB - deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopB));

    /**
     * Check if the predictedArrivalTime is 5 min less then the scheduled
     * arrival time for stop B.
     */
    assertEquals(scheduledArrivalTimeForStopB - deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB));
  }

  /**
   * This method tests a request for an arrival time for a stop, when the
   * current time is greater than the arrival time prediction for that stop
   * (Stop B). In other words, the bus is predicted to have already passed the
   * stop (Stop B).
   * 
   * Test configuration: There are 2 bus stops which have the real time arrival
   * times (time point predictions) - Stop A and B. In this case
   * getArrivalsAndDeparturesForStopInTimeRange() should return last received
   * time point prediction for particular stop we're requesting information for.
   * 
   * Current time = 14:00 
   *          Schedule time    Real-time from feed
   * Stop A   13:30            13:30
   * Stop B   13:40            13:50
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange05() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 30));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripA.getId());

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(13, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(
        tprA, tprB));

    long predictedArrivalTimeStopA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    /**
     * Check if the predictedArrivalTime is exactly the same as
     * TimepointPrediction for both stops
     */
    assertEquals(tprA.getTimepointPredictedArrivalTime(),
        predictedArrivalTimeStopA);
    assertEquals(tprB.getTimepointPredictedArrivalTime(),
        predictedArrivalTimeStopB);

    /**
     * Check if the predictedDepartureTimes and scheduledDepartureTimes have the
     * same delta as arrival predictions and scheduled arrival times for both
     * stops
     */
    long predictedDepartureTimeStopA = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());

    long scheduledArrivalTimeForStopA = getScheduledArrivalTimeByStopId(mTripA,
        mStopA.getId());
    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledDepartureTimeForStopA = getScheduledDepartureTimeByStopId(
        mTripA, mStopA.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(
        mTripA, mStopB.getId());

    long deltaA = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopA)
        - scheduledArrivalTimeForStopA;
    assertEquals(scheduledDepartureTimeForStopA + deltaA,
        TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopA));

    long deltaB = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB)
        - scheduledArrivalTimeForStopB;
    assertEquals(scheduledDepartureTimeForStopB + deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopB));
  }

  /**
   * This method tests to make sure upstream propagation isn't happening.
   * 
   * Test configuration: Time point predictions are downstream of Stop A, which
   * means that the bus is predicted to have already passed the bus stop. There
   * only one bus stop (Stop B) which has a real time arrival time (time point
   * prediction). In this case getArrivalsAndDeparturesForStopInTimeRange() for
   * Stop A should return a predicted arrival time = 0, indicating that no
   * real-time information is available for Stop A.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed
   * Stop A   13:30            -----
   * Stop B   13:45            13:40
   * 
   * Since the bus already passed the bus stop A, and no real-time information
   * is available for Stop A, OBA should NOT propagate arrival estimate for Stop
   * B upstream to Stop A.
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange06() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(13, 40));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprB));

    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    /**
     * Check if the predictedArrivalTime for stop B is exactly the same as
     * TimepointPredictionRecord.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime(),
        predictedArrivalTimeStopB);

    /**
     * Check predicted departure for Stop B too, to make sure its propagated
     * from provided predicted arrival time
     */
    long scheduledArrivalTimeForStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledDepartureTimeForStopB = getScheduledDepartureTimeByStopId(
        mTripA, mStopB.getId());
    long predictedDepartureTimeStopB = getPredictedDepartureTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    long deltaB = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB)
        - scheduledArrivalTimeForStopB;
    assertEquals(scheduledDepartureTimeForStopB + deltaB,
        TimeUnit.MILLISECONDS.toSeconds(predictedDepartureTimeStopB));

    /**
     * Make sure the predictedArrivalTime for stop A is equals to 0 - in other
     * words, we should show no real-time information for this stop and use the
     * scheduled time instead.
     */

    long predictedArrivalTimeA = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopA.getId());
    assertEquals(0, predictedArrivalTimeA);
  }
  
  /**
   * This method tests loop routes and make sure
   * propagation isn't happening, if there is only one prediction.
   * 
   * Test configuration: There is only one time point prediction for the first stop.
   * But time point predictions does not have stop sequences.
   * In this case getArrivalsAndDeparturesForStopInTimeRange() for
   * Stop A should return a predicted arrival time = 0, indicating that no
   * real-time information is available for Stop A.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence
   * Stop A   13:30            13:35                          0
   * Stop B   13:45            -----                          1
   * Stop A   13:55            -----                          2
   * 
   * Since we
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange07() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

    // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprBTime = createPredictedTime(time(13, 35));
    tprA.setTimepointPredictedArrivalTime(tprBTime);
    tprA.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprA), mStopA);


    /**
     * Make sure the predictedArrivalTime for stop A (first and the last stop)  is equals to 0 - in other
     * words, we should show no real-time information for this stop and use the
     * scheduled time instead.
     */

    //
    long predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 0);
    assertEquals(0, predictedArrivalTimeA);
    
    predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 2);
    assertEquals(0, predictedArrivalTimeA);
  }

  /**
   * This method tests loop routes and make sure
   * propagation isn't happening for the last stop, if there are predictions for the first stop
   * and after first stop
   * 
   * Test configuration: There are two time point predictions for the first and the next stop.
   * But time point predictions does not have stop sequences.
   * In this case getArrivalsAndDeparturesForStopInTimeRange() for the last
   * Stop A should return a predicted arrival based on stop B (6 min delay). On the other hand, the first Stop A 
   * and stop B predictions should match with time point predictions.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence
   * Stop A   13:30            13:35                          0
   * Stop B   13:45            13:51                          1
   * Stop A   13:55            -----                          2
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange08() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 35));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripA.getId());
    
    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(13, 51));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprA, tprB), mStopB);

    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    /**
     * Check if the predictedArrivalTime for stop B is exactly the same as
     * TimepointPredictionRecord.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime(),
        predictedArrivalTimeStopB);

    /**
     * Make sure the predictedArrivalTime for stop A (the first stop)  is exactly the same as
     * TimepointPredictionRecord.
     */
    long predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 0);
    assertEquals(tprA.getTimepointPredictedArrivalTime(), predictedArrivalTimeA);
    
    /**
     * Make sure the predictedArrivalTime for stop A (the last stop)  is propogated based on
     * tpr of the stop B
     */
    
    long scheduledArrivalTimeStopB = getScheduledArrivalTimeByStopId(mTripA,
        mStopB.getId());
    long scheduledArrivalTimeLastStopA = getScheduledArrivalTimeByStopId(mTripA,
        mStopA.getId(), 2);

    /**
     * Calculate the delay of the previous stop(stop B)
     */
    long delta = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopB)
        - scheduledArrivalTimeStopB;

    predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 2);

    assertEquals(scheduledArrivalTimeLastStopA + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeA));
  }
  
  /**
   * This method tests loop routes and make sure
   * propagation isn't happening for the last stop, if there are predictions for the first stop
   * and after first stop
   * 
   * Test configuration: There are two time point predictions for the first and the next stop.
   * But time point predictions does not have stop sequences.
   * In this case getArrivalsAndDeparturesForStopInTimeRange() for the last
   * Stop A should return a predicted arrival time = 0, indicating that no
   * real-time information is available for the last Stop A. On the other hand, the first Stop A 
   * and stop B predictions should match with time point predictions.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence
   * Stop A   13:30            -----                          0
   * Stop B   13:45            13:50                          1
   * Stop A   13:55            13:58                          2
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange09() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(13, 58));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripA.getId());
    
    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(13, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripA.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecord(Arrays.asList(tprB, tprA), mStopB);

    long predictedArrivalTimeStopB = getPredictedArrivalTimeByStopId(
        arrivalsAndDepartures, mStopB.getId());
    /**
     * Check if the predictedArrivalTime for stop B is exactly the same as
     * TimepointPredictionRecord.
     */
    assertEquals(tprB.getTimepointPredictedArrivalTime(),
        predictedArrivalTimeStopB);

    /**
     * Make sure the predictedArrivalTime for stop A (the first stop)  is exactly the same as
     * TimepointPredictionRecord.
     */
    long predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 2);
    assertEquals(tprA.getTimepointPredictedArrivalTime(), predictedArrivalTimeA);
    
    /**
     * Make sure the predictedArrivalTime for stop A (the last stop)  is equals to 0 - in other
     * words, we should show no real-time information for this stop and use the
     * scheduled time instead.
     */
    predictedArrivalTimeA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), 0);
    assertEquals(0, predictedArrivalTimeA);
  }
  
  /**
   * This method tests loop routes and make sure upstream
   * propagation isn't happening 
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            -----                          2           t1
   * 
   * Stop A   14:05            -----                          0           t2
   * Stop B   14:15            14:25                          1           t2
   * Stop A   14:25            14:35                          2           t2
   * 
   * Stop A   14:30            -----                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange10() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 25));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripB.getId());
    
    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(14, 35));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripB.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprB, tprA), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    /**
     * Check the upstream stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);

    /**
     * Make sure the predictedArrivalTime for stop A (the last stop) and the stop B in 
     * the trip B is exactly the same as TimepointPredictionRecord.
     */
    long predictedArrivalTimeBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    
    assertEquals(tprA.getTimepointPredictedArrivalTime(), predictedArrivalTimeBC);
    assertEquals(tprB.getTimepointPredictedArrivalTime(), predictedArrivalTimeBB);
    
    /**
     * Make sure the predictions happening downstream based on the last stop
     * of the trip B
     */
    
    long scheduledArrivalTimeBC = getScheduledArrivalTimeByStopId(mTripB,
        mStopA.getId(), 2);
    // Calculate the delay of the last stop A in trip B
    long delta = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeBC)
        - scheduledArrivalTimeBC;
    
    long scheduledArrivalTimeStopCA = getScheduledArrivalTimeByStopId(mTripC,
        mStopA.getId(), 0);
    long predictedArrivalTimeCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    
    long scheduledArrivalTimeStopCB = getScheduledArrivalTimeByStopId(mTripC,
        mStopB.getId(), 1);
    long predictedArrivalTimeCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    
    long scheduledArrivalTimeStopCC = getScheduledArrivalTimeByStopId(mTripC,
        mStopA.getId(), 2);
    long predictedArrivalTimeCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);

    assertEquals(scheduledArrivalTimeStopCA + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCA));
    assertEquals(scheduledArrivalTimeStopCB + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCB));
    assertEquals(scheduledArrivalTimeStopCC + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCC));
  }
  
  /**
   * This method tests loop routes and make sure upstream
   * propagation isn't happening 
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            -----                          2           t1
   * 
   * Stop A   14:05            14:10                          0           t2
   * Stop B   14:15            14:25                          1           t2
   * Stop A   14:25            -----                          2           t2
   * 
   * Stop A   14:30            -----                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange11() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 10));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripB.getId());
    
    // Set time point predictions for stop B
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(14, 25));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripB.getId());

    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprA, tprB), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    
    /**
     * Check the upstream stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);

    /**
     * Make sure the predictedArrivalTime for stop A (the last stop) and the stop B in 
     * the trip B is exactly the same as TimepointPredictionRecord.
     */
    long predictedArrivalTimeBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    
    assertEquals(tprA.getTimepointPredictedArrivalTime(), predictedArrivalTimeBA);
    assertEquals(tprB.getTimepointPredictedArrivalTime(), predictedArrivalTimeBB);
    
    /**
     * Make sure the predictions happening downstream based on the last stop
     * of the trip B
     */
    
    long scheduledArrivalTimeBB = getScheduledArrivalTimeByStopId(mTripB,
        mStopB.getId(), 1);
    // Calculate the delay of the last stop A in trip B
    long delta = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeBB)
        - scheduledArrivalTimeBB;
    
    long scheduledArrivalTimeStopBC = getScheduledArrivalTimeByStopId(mTripB,
        mStopA.getId(), 2);
    long predictedArrivalTimeBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    
    long scheduledArrivalTimeStopCA = getScheduledArrivalTimeByStopId(mTripC,
        mStopA.getId(), 0);
    long predictedArrivalTimeCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    
    long scheduledArrivalTimeStopCB = getScheduledArrivalTimeByStopId(mTripC,
        mStopB.getId(), 1);
    long predictedArrivalTimeCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    
    long scheduledArrivalTimeStopCC = getScheduledArrivalTimeByStopId(mTripC,
        mStopA.getId(), 2);
    long predictedArrivalTimeCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);

    assertEquals(scheduledArrivalTimeStopBC + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeBC));
    assertEquals(scheduledArrivalTimeStopCA + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCA));
    assertEquals(scheduledArrivalTimeStopCB + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCB));
    assertEquals(scheduledArrivalTimeStopCC + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCC));
  }
  
  /**
   * This method tests loop routes and make sure time point predictions
   * are dropped if there is only one prediction for the first or the last stop in a loop route
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            -----                          2           t1
   * 
   * Stop A   14:05            14:10                          0           t2
   * Stop B   14:15            -----                          1           t2
   * Stop A   14:25            -----                          2           t2
   * 
   * Stop A   14:30            -----                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange12() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 10));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripB.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprA), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeStopBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeStopBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    long predictedArrivalTimeStopCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    long predictedArrivalTimeStopCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    long predictedArrivalTimeStopCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);
    
    /**
     * Check the all stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);
    assertEquals(predictedArrivalTimeStopBB, 0);
    assertEquals(predictedArrivalTimeStopBC, 0);
    assertEquals(predictedArrivalTimeStopCA, 0);
    assertEquals(predictedArrivalTimeStopCB, 0);
    assertEquals(predictedArrivalTimeStopCC, 0);
  }
  
  /**
   * This method tests loop routes and make sure time point predictions
   * are dropped if there is only one prediction for the first or the last stop in a loop route
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            14:00                          2           t1
   * 
   * Stop A   14:05            14:10                          0           t2
   * Stop B   14:15            -----                          1           t2
   * Stop A   14:25            -----                          2           t2
   * 
   * Stop A   14:30            -----                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange13() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for previous stop A
    TimepointPredictionRecord tprAA = new TimepointPredictionRecord();
    tprAA.setTimepointId(mStopA.getId());
    long tprAATime = createPredictedTime(time(14, 00));
    tprAA.setTimepointPredictedArrivalTime(tprAATime);
    tprAA.setTripId(mTripA.getId());
    
 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 10));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripB.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprAA, tprA), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeStopBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeStopBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    long predictedArrivalTimeStopCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    long predictedArrivalTimeStopCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    long predictedArrivalTimeStopCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);
    
    /**
     * Check the all stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);
    assertEquals(predictedArrivalTimeStopBB, 0);
    assertEquals(predictedArrivalTimeStopBC, 0);
    assertEquals(predictedArrivalTimeStopCA, 0);
    assertEquals(predictedArrivalTimeStopCB, 0);
    assertEquals(predictedArrivalTimeStopCC, 0);
  }
  
  /**
   * This method tests loop routes and make sure time point predictions
   * are dropped if there is only one prediction for the first or the last stop in a loop route
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            -----                          2           t1
   * 
   * Stop A   14:05            -----                          0           t2
   * Stop B   14:15            -----                          1           t2
   * Stop A   14:25            14:30                          2           t2
   * 
   * Stop A   14:30            14:40                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange14() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for previous stop A
    TimepointPredictionRecord tprAA = new TimepointPredictionRecord();
    tprAA.setTimepointId(mStopA.getId());
    long tprAATime = createPredictedTime(time(14, 30));
    tprAA.setTimepointPredictedArrivalTime(tprAATime);
    tprAA.setTripId(mTripB.getId());
    
 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 40));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripC.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprAA, tprA), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeStopBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeStopBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    long predictedArrivalTimeStopCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    long predictedArrivalTimeStopCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    long predictedArrivalTimeStopCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);
    
    /**
     * Check the all stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);
    assertEquals(predictedArrivalTimeStopBB, 0);
    assertEquals(predictedArrivalTimeStopBC, 0);
    assertEquals(predictedArrivalTimeStopCA, 0);
    assertEquals(predictedArrivalTimeStopCB, 0);
    assertEquals(predictedArrivalTimeStopCC, 0);
  }
  
  /**
   * This method tests loop routes and make sure time point predictions
   * are dropped if there is only one prediction for the first or the last stop in a loop route
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            14:00                          2           t1
   * 
   * Stop A   14:05            -----                          0           t2
   * Stop B   14:15            -----                          1           t2
   * Stop A   14:25            -----                          2           t2
   * 
   * Stop A   14:30            14:40                          0           t3
   * Stop B   14:45            -----                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange15() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for previous stop A
    TimepointPredictionRecord tprAA = new TimepointPredictionRecord();
    tprAA.setTimepointId(mStopA.getId());
    long tprAATime = createPredictedTime(time(14, 00));
    tprAA.setTimepointPredictedArrivalTime(tprAATime);
    tprAA.setTripId(mTripA.getId());
    
 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 40));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripC.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprAA, tprA), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeStopBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeStopBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    long predictedArrivalTimeStopCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    long predictedArrivalTimeStopCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    long predictedArrivalTimeStopCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);
    
    /**
     * Check the all stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);
    assertEquals(predictedArrivalTimeStopBB, 0);
    assertEquals(predictedArrivalTimeStopBC, 0);
    assertEquals(predictedArrivalTimeStopCA, 0);
    assertEquals(predictedArrivalTimeStopCB, 0);
    assertEquals(predictedArrivalTimeStopCC, 0);
  }
  
  /**
   * This method tests loop routes and make sure time point predictions
   * are dropped if there is only one prediction for the first or the last stop in a loop route
   * 
   * Test configuration: There are three different loop trips and each trip has 3 stops
   * Time point predictions does not have stop sequences.
   * 
   * Current time = 14:00
   *          Schedule time    Real-time from feed      stop_sequence  trip_id
   * Stop A   13:30            -----                          0           t1
   * Stop B   13:45            -----                          1           t1
   * Stop A   13:55            14:00                          2           t1
   * 
   * Stop A   14:05            -----                          0           t2
   * Stop B   14:15            -----                          1           t2
   * Stop A   14:25            -----                          2           t2
   * 
   * Stop A   14:30            14:40                          0           t3
   * Stop B   14:45            14:50                          1           t3
   * Stop A   14:55            -----                          2           t3
   * 
   */
  @Test
  public void testGetArrivalsAndDeparturesForStopInTimeRange16() {
    // Override the current time with a later time than the time point
    // predictions
    mCurrentTime = dateAsLong("2015-07-23 14:00");

 // Set time point predictions for previous stop A
    TimepointPredictionRecord tprAA = new TimepointPredictionRecord();
    tprAA.setTimepointId(mStopA.getId());
    long tprAATime = createPredictedTime(time(14, 00));
    tprAA.setTimepointPredictedArrivalTime(tprAATime);
    tprAA.setTripId(mTripA.getId());
    
 // Set time point predictions for stop A
    TimepointPredictionRecord tprA = new TimepointPredictionRecord();
    tprA.setTimepointId(mStopA.getId());
    long tprATime = createPredictedTime(time(14, 40));
    tprA.setTimepointPredictedArrivalTime(tprATime);
    tprA.setTripId(mTripC.getId());
    
    TimepointPredictionRecord tprB = new TimepointPredictionRecord();
    tprB.setTimepointId(mStopB.getId());
    long tprBTime = createPredictedTime(time(14, 50));
    tprB.setTimepointPredictedArrivalTime(tprBTime);
    tprB.setTripId(mTripC.getId());
    
    // Call ArrivalsAndDeparturesForStopInTimeRange method in
    // ArrivalAndDepartureServiceImpl
    List<ArrivalAndDepartureInstance> arrivalsAndDepartures = 
        getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(Arrays.asList(tprAA, tprA, tprB), mStopB);

    long predictedArrivalTimeStopAA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 0);
    long predictedArrivalTimeStopAB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripA.getId(), 1);
    long predictedArrivalTimeStopAC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripA.getId(), 2);
    long predictedArrivalTimeStopBA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 0);
    long predictedArrivalTimeStopBB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripB.getId(), 1);
    long predictedArrivalTimeStopBC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripB.getId(), 2);
    long predictedArrivalTimeStopCA = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 0);
    long predictedArrivalTimeStopCB = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopB.getId(), mTripC.getId(), 1);
    
    /**
     * Check the all stops and make sure no propagation happening.
     */
    assertEquals(predictedArrivalTimeStopAA, 0);
    assertEquals(predictedArrivalTimeStopAB, 0);
    assertEquals(predictedArrivalTimeStopAC, 0);
    assertEquals(predictedArrivalTimeStopBA, 0);
    assertEquals(predictedArrivalTimeStopBB, 0);
    assertEquals(predictedArrivalTimeStopBC, 0);
    
    /**
     * Check last three predictions
     * We should have predictions for these stops
     */
    assertEquals(predictedArrivalTimeStopCA, tprA.getTimepointPredictedArrivalTime());
    assertEquals(predictedArrivalTimeStopCB, tprB.getTimepointPredictedArrivalTime());
    
    long scheduledArrivalTimeCB = getScheduledArrivalTimeByStopId(mTripC,
        mStopB.getId(), 1);
    // Calculate the delay of the last stop A in trip B
    long delta = TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeStopCB)
        - scheduledArrivalTimeCB;
    
    long scheduledArrivalTimeStopCC = getScheduledArrivalTimeByStopId(mTripC,
        mStopA.getId(), 2);
    long predictedArrivalTimeCC = getPredictedArrivalTimeByStopIdAndSequence(
        arrivalsAndDepartures, mStopA.getId(), mTripC.getId(), 2);

    assertEquals(scheduledArrivalTimeStopCC + delta , TimeUnit.MILLISECONDS.toSeconds(predictedArrivalTimeCC));
  }
  
  /**
   * Set up the BlockLocationServiceImpl for the test, using the given
   * timepointPredictions
   * 
   * @param timepointPredictions real-time predictions to apply to the
   *          BlockLocationServiceImpl
   * @return a list of ArrivalAndDepartureInstances which is used to access
   *         predicted arrival/departure times for a stop, for comparison
   *         against the expected values
   */
  private List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForStopInTimeRangeByTimepointPredictionRecord(
      List<TimepointPredictionRecord> timepointPredictions) {
    TargetTime target = new TargetTime(mCurrentTime, mCurrentTime);

    // Setup block
    BlockEntryImpl block = block("blockA");

    stopTime(0, mStopA, mTripA, time(13, 30), time(13, 35), 1000);
    stopTime(1, mStopB, mTripA, time(13, 45), time(13, 50), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), mTripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(0);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, mServiceDate);
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
        _stopTimeService.getStopTimeInstancesInTimeRange(mStopB,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2));

    // Create and add vehicle location record cache
    VehicleLocationRecordCacheImpl _cache = new VehicleLocationRecordCacheImpl();
    VehicleLocationRecord vlr = new VehicleLocationRecord();
    vlr.setBlockId(blockLocationB.getBlockInstance().getBlock().getBlock().getId());
    vlr.setTripId(mTripA.getId());
    vlr.setTimepointPredictions(blockLocationB.getTimepointPredictions());
    vlr.setTimeOfRecord(mCurrentTime);
    vlr.setVehicleId(new AgencyAndId("1", "123"));

    // Create ScheduledBlockLocation for cache
    ScheduledBlockLocation sbl = new ScheduledBlockLocation();
    sbl.setActiveTrip(blockLocationB.getActiveTrip());

    // Add data to cache
    _cache.addRecord(blockInstance, vlr, sbl, null);
    _blockLocationService.setVehicleLocationRecordCache(_cache);
    ScheduledBlockLocationServiceImpl scheduledBlockLocationServiceImpl = new ScheduledBlockLocationServiceImpl();
    _blockLocationService.setScheduledBlockLocationService(scheduledBlockLocationServiceImpl);

    // Call ArrivalAndDepartureService
    return _service.getArrivalsAndDeparturesForStopInTimeRange(mStopB, target,
        stopTimeFrom, stopTimeTo);
  }
  
  /**
   * Set up the BlockLocationServiceImpl for the test, using the given
   * timepointPredictions
   * 
   * @param timepointPredictions real-time predictions to apply to the
   *          BlockLocationServiceImpl
   * @return a list of ArrivalAndDepartureInstances which is used to access
   *         predicted arrival/departure times for a stop, for comparison
   *         against the expected values
   */
  private List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecord(
      List<TimepointPredictionRecord> timepointPredictions, StopEntryImpl stop) {
    TargetTime target = new TargetTime(mCurrentTime, mCurrentTime);

    // Setup block
    BlockEntryImpl block = block("blockA");

    stopTime(0, mStopA, mTripA, time(13, 30), time(13, 35), 1000);
    stopTime(1, mStopB, mTripA, time(13, 45), time(13, 50), 2000);
    stopTime(2, mStopA, mTripA, time(13, 55), time(13, 55), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA"), lsids()), mTripA);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(2);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, mServiceDate);
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

    StopTimeInstance sti1 = new StopTimeInstance(bstAA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in1 = new ArrivalAndDepartureInstance(sti1);
    in1.setBlockLocation(blockLocationB);
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime()));
    in1.setPredictedDepartureTime((long) (in1.getScheduledDepartureTime()));

    StopTimeInstance sti2 = new StopTimeInstance(bstAB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in2 = new ArrivalAndDepartureInstance(sti2);
    in2.setBlockLocation(blockLocationB);
    
    StopTimeInstance sti3 = new StopTimeInstance(bstBA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in3 = new ArrivalAndDepartureInstance(sti3);
    in3.setBlockLocation(blockLocationB);
    in3.setPredictedArrivalTime((long) (in3.getScheduledArrivalTime()));
    in3.setPredictedDepartureTime((long) (in3.getScheduledDepartureTime()));

    Date fromTimeBuffered = new Date(stopTimeFrom
        - _blockStatusService.getRunningLateWindow() * 1000);
    Date toTimeBuffered = new Date(stopTimeTo
        + _blockStatusService.getRunningEarlyWindow() * 1000);

    Mockito.when(
        _stopTimeService.getStopTimeInstancesInTimeRange(stop,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2, sti3));

    // Create and add vehicle location record cache
    VehicleLocationRecordCacheImpl _cache = new VehicleLocationRecordCacheImpl();
    VehicleLocationRecord vlr = new VehicleLocationRecord();
    vlr.setBlockId(blockLocationB.getBlockInstance().getBlock().getBlock().getId());
    vlr.setTripId(mTripA.getId());
    vlr.setTimepointPredictions(blockLocationB.getTimepointPredictions());
    vlr.setTimeOfRecord(mCurrentTime);
    vlr.setVehicleId(new AgencyAndId("1", "123"));

    // Create ScheduledBlockLocation for cache
    ScheduledBlockLocation sbl = new ScheduledBlockLocation();
    sbl.setActiveTrip(blockLocationB.getActiveTrip());

    // Add data to cache
    _cache.addRecord(blockInstance, vlr, sbl, null);
    _blockLocationService.setVehicleLocationRecordCache(_cache);
    ScheduledBlockLocationServiceImpl scheduledBlockLocationServiceImpl = new ScheduledBlockLocationServiceImpl();
    _blockLocationService.setScheduledBlockLocationService(scheduledBlockLocationServiceImpl);

    // Call ArrivalAndDepartureService
    return _service.getArrivalsAndDeparturesForStopInTimeRange(stop, target,
        stopTimeFrom, stopTimeTo);
  }
  
  /**
   * Set up the BlockLocationServiceImpl for the test, using the given
   * timepointPredictions
   * 
   * @param timepointPredictions real-time predictions to apply to the
   *          BlockLocationServiceImpl
   * @return a list of ArrivalAndDepartureInstances which is used to access
   *         predicted arrival/departure times for a stop, for comparison
   *         against the expected values
   */
  private List<ArrivalAndDepartureInstance> getArrivalsAndDeparturesForLoopRouteInTimeRangeByTimepointPredictionRecordWithMultipleTrips(
      List<TimepointPredictionRecord> timepointPredictions, StopEntryImpl stop) {
    TargetTime target = new TargetTime(mCurrentTime, mCurrentTime);

    // Setup block
    BlockEntryImpl block = block("blockA");

    stopTime(0, mStopA, mTripA, time(13, 30), time(13, 35), 1000);
    stopTime(1, mStopB, mTripA, time(13, 45), time(13, 50), 2000);
    stopTime(2, mStopA, mTripA, time(13, 55), time(13, 55), 2000);
    
    stopTime(0, mStopA, mTripB, time(14, 05), time(14, 10), 1000);
    stopTime(1, mStopB, mTripB, time(14, 15), time(14, 20), 2000);
    stopTime(2, mStopA, mTripB, time(14, 25), time(14, 25), 2000);
    
    stopTime(0, mStopA, mTripC, time(14, 30), time(14, 35), 1000);
    stopTime(1, mStopB, mTripC, time(14, 45), time(14, 50), 2000);
    stopTime(2, mStopA, mTripC, time(14, 55), time(14, 55), 2000);

    BlockConfigurationEntry blockConfig = blockConfiguration(block,
        serviceIds(lsids("sA", "sB", "sC"), lsids()), mTripA, mTripB, mTripC);
    BlockStopTimeEntry bstAA = blockConfig.getStopTimes().get(0);
    BlockStopTimeEntry bstAB = blockConfig.getStopTimes().get(1);
    BlockStopTimeEntry bstAC = blockConfig.getStopTimes().get(2);
    BlockStopTimeEntry bstBA = blockConfig.getStopTimes().get(3);
    BlockStopTimeEntry bstBB = blockConfig.getStopTimes().get(4);
    BlockStopTimeEntry bstBC = blockConfig.getStopTimes().get(5);
    BlockStopTimeEntry bstCA = blockConfig.getStopTimes().get(6);
    BlockStopTimeEntry bstCB = blockConfig.getStopTimes().get(7);
    BlockStopTimeEntry bstCC = blockConfig.getStopTimes().get(8);

    // Setup block location instance for trip B
    BlockInstance blockInstance = new BlockInstance(blockConfig, mServiceDate);
    BlockLocation blockLocationB = new BlockLocation();
    blockLocationB.setActiveTrip(bstBB.getTrip());
    blockLocationB.setBlockInstance(blockInstance);
    blockLocationB.setClosestStop(bstBC);
    blockLocationB.setDistanceAlongBlock(400);
    blockLocationB.setInService(true);
    blockLocationB.setNextStop(bstBC);
    blockLocationB.setPredicted(false);
    blockLocationB.setScheduledDistanceAlongBlock(400);

    blockLocationB.setTimepointPredictions(timepointPredictions);

    // Mock StopTimeInstance with time frame
    long stopTimeFrom = dateAsLong("2015-07-23 00:00");
    long stopTimeTo = dateAsLong("2015-07-24 00:00");

    StopTimeInstance sti1 = new StopTimeInstance(bstAA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in1 = new ArrivalAndDepartureInstance(sti1);
    in1.setBlockLocation(blockLocationB);
    in1.setPredictedArrivalTime((long) (in1.getScheduledArrivalTime()));
    in1.setPredictedDepartureTime((long) (in1.getScheduledDepartureTime()));

    StopTimeInstance sti2 = new StopTimeInstance(bstAB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in2 = new ArrivalAndDepartureInstance(sti2);
    in2.setBlockLocation(blockLocationB);
    
    StopTimeInstance sti3 = new StopTimeInstance(bstAC,
        blockInstance.getState());
    ArrivalAndDepartureInstance in3 = new ArrivalAndDepartureInstance(sti3);
    in3.setBlockLocation(blockLocationB);
    in3.setPredictedArrivalTime((long) (in3.getScheduledArrivalTime()));
    in3.setPredictedDepartureTime((long) (in3.getScheduledDepartureTime()));
    
    StopTimeInstance sti4 = new StopTimeInstance(bstBA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in4 = new ArrivalAndDepartureInstance(sti4);
    in4.setBlockLocation(blockLocationB);

    StopTimeInstance sti5 = new StopTimeInstance(bstBB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in5 = new ArrivalAndDepartureInstance(sti5);
    in5.setBlockLocation(blockLocationB);
    
    StopTimeInstance sti6 = new StopTimeInstance(bstBC,
        blockInstance.getState());
    ArrivalAndDepartureInstance in6 = new ArrivalAndDepartureInstance(sti6);
    in6.setBlockLocation(blockLocationB);

    StopTimeInstance sti7 = new StopTimeInstance(bstCA,
        blockInstance.getState());
    ArrivalAndDepartureInstance in7 = new ArrivalAndDepartureInstance(sti7);
    in7.setBlockLocation(blockLocationB);

    StopTimeInstance sti8 = new StopTimeInstance(bstCB,
        blockInstance.getState());
    ArrivalAndDepartureInstance in8 = new ArrivalAndDepartureInstance(sti8);
    in8.setBlockLocation(blockLocationB);
    
    StopTimeInstance sti9 = new StopTimeInstance(bstCC,
        blockInstance.getState());
    ArrivalAndDepartureInstance in9 = new ArrivalAndDepartureInstance(sti9);
    in9.setBlockLocation(blockLocationB);

    Date fromTimeBuffered = new Date(stopTimeFrom
        - _blockStatusService.getRunningLateWindow() * 1000);
    Date toTimeBuffered = new Date(stopTimeTo
        + _blockStatusService.getRunningEarlyWindow() * 1000);

    Mockito.when(
        _stopTimeService.getStopTimeInstancesInTimeRange(stop,
            fromTimeBuffered, toTimeBuffered,
            EFrequencyStopTimeBehavior.INCLUDE_UNSPECIFIED)).thenReturn(
        Arrays.asList(sti1, sti2, sti3, sti4, sti5, sti6, sti7, sti8, sti9));

    // Create and add vehicle location record cache
    VehicleLocationRecordCacheImpl _cache = new VehicleLocationRecordCacheImpl();
    VehicleLocationRecord vlr = new VehicleLocationRecord();
    vlr.setBlockId(blockLocationB.getBlockInstance().getBlock().getBlock().getId());
    vlr.setTripId(mTripB.getId());
    vlr.setTimepointPredictions(blockLocationB.getTimepointPredictions());
    vlr.setTimeOfRecord(mCurrentTime);
    vlr.setVehicleId(new AgencyAndId("1", "123"));

    // Create ScheduledBlockLocation for cache
    ScheduledBlockLocation sbl = new ScheduledBlockLocation();
    sbl.setActiveTrip(blockLocationB.getActiveTrip());

    // Add data to cache
    _cache.addRecord(blockInstance, vlr, sbl, null);
    _blockLocationService.setVehicleLocationRecordCache(_cache);
    ScheduledBlockLocationServiceImpl scheduledBlockLocationServiceImpl = new ScheduledBlockLocationServiceImpl();
    _blockLocationService.setScheduledBlockLocationService(scheduledBlockLocationServiceImpl);

    // Call ArrivalAndDepartureService
    return _service.getArrivalsAndDeparturesForStopInTimeRange(stop, target,
        stopTimeFrom, stopTimeTo);
  }

  //
  // Helper methods
  //

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
  
  private long getPredictedArrivalTimeByStopIdAndSequence(
      List<ArrivalAndDepartureInstance> arrivalsAndDepartures,
      AgencyAndId stopId, int sequence) {
    for (ArrivalAndDepartureInstance adi : arrivalsAndDepartures) {
      if (adi.getStop().getId().equals(stopId) 
          && adi.getStopTimeInstance().getSequence() == sequence) {
        return adi.getPredictedArrivalTime();
      }
    }
    return 0;
  }
  
  private long getPredictedArrivalTimeByStopIdAndSequence(
      List<ArrivalAndDepartureInstance> arrivalsAndDepartures,
      AgencyAndId stopId, AgencyAndId tripId, int sequence) {
    for (ArrivalAndDepartureInstance adi : arrivalsAndDepartures) {
      if (adi.getStop().getId().equals(stopId) 
          && adi.getStopTimeInstance().getStopTime().getStopTime().getSequence() == sequence
          && tripId.equals(adi.getStopTimeInstance().getTrip().getTrip().getId())) {
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

  private long getScheduledArrivalTimeByStopId(TripEntryImpl trip,
      AgencyAndId id) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id)) {
        return ste.getArrivalTime() + mServiceDate / 1000;
      }
    }
    return 0;
  }
  
  private long getScheduledArrivalTimeByStopId(TripEntryImpl trip,
      AgencyAndId id, int sequence) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id) && sequence == ste.getSequence()) {
        return ste.getArrivalTime() + mServiceDate / 1000;
      }
    }
    return 0;
  }

  private long getScheduledDepartureTimeByStopId(TripEntryImpl trip,
      AgencyAndId id) {
    for (StopTimeEntry ste : trip.getStopTimes()) {
      if (ste.getStop().getId().equals(id)) {
        return ste.getDepartureTime() + mServiceDate / 1000;
      }
    }
    return 0;
  }

  private long createPredictedTime(int time) {
    return (mServiceDate / 1000 + time) * 1000;
  }
}