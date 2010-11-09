package org.onebusaway.transit_data_federation.services.transit_graph;

public interface StopTimeEntry {

  public int getId();

  public TripEntry getTrip();

  public int getSequence();

  public StopEntry getStop();

  /**
   * @return arrival time, in seconds since midnight
   */
  public int getArrivalTime();

  /**
   * @return departure time, in seconds since midnight
   */
  public int getDepartureTime();

  public int getPickupType();

  public int getDropOffType();

  public double getShapeDistTraveled();

  /**
   * The amount of slack time at the current stop time. Slack time usually
   * results from a delay between the arrival and departure time at a given stop
   * that could be shortened if the vehicle is running late.
   * 
   * @return slack time, in seconds
   */
  public int getSlackTime();

  /**
   * The amount of accumulated slack time from the start of the trip to the
   * arrival time at this stop. Slack time accumulates when there is scheduled
   * time between the arrival and departure of a vehicle at a stop that could
   * potentially be shortened if the vehicle is running late.
   * 
   * @return the accumulated slack time, in seconds
   */
  public int getAccumulatedSlackTime();
}