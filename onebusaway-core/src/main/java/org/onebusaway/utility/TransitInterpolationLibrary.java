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
package org.onebusaway.utility;

import java.util.Arrays;

/**
 * Transit specific methods to support interpolation of values against a sorted key-value
 * map given a new target key.
 */
public class TransitInterpolationLibrary {

  private static final String OUT_OF_RANGE = "attempt to interpolate key outside range of key-value data";

  public static Double interpolate(double[] keys, double[] values,
      double target, EOutOfRangeStrategy outOfRangeStrategy) {
    return interpolate(keys, values, target, outOfRangeStrategy, null);
  }

  /**
   * Given sorted keys and values arrays, interpolate using 
   * linear interpolation a value for a target
   * key within the key-range of the map. For a key outside the range of the
   * keys of the map, the {@code outOfRange} {@link EOutOfRangeStrategy}
   * strategy will determine the interpolation behavior.
   * 
   * @param keys sorted array of keys
   * @param values sorted arrays of values 
   * @param target the target key used to interpolate a value
   * @param outOfRangeStrategy the strategy to use for a target key that outside
   *          the key-range of the value map
   * @param inRangeStrategy the strategy to use for a target key that inside
   *          the key-range of the value map
   * @return null if the propagation is for upstream values, otherwise
   *          an interpolated value for the target key
   */
  public static Double interpolate(double[] keys, double[] values,
      double target, EOutOfRangeStrategy outOfRangeStrategy,
      EInRangeStrategy inRangeStrategy) {

    if (values.length == 0)
      throw new IndexOutOfBoundsException(OUT_OF_RANGE);

    int index = Arrays.binarySearch(keys, target);
    if (index >= 0)
      return values[index];

    index = -(index + 1);

    if (index == values.length) {
      switch (outOfRangeStrategy) {
        case INTERPOLATE:
          if (values.length > 1)
            return InterpolationLibrary.interpolatePair(keys[index - 2],
                values[index - 2], keys[index - 1], values[index - 1], target);
          return values[index - 1];
        case LAST_VALUE:
          return values[index - 1];
        case EXCEPTION:
          throw new IndexOutOfBoundsException(OUT_OF_RANGE);
      }
    }

    if (index == 0) {
      switch (outOfRangeStrategy) {
        case INTERPOLATE:
          if (values.length > 1)
            return InterpolationLibrary.interpolatePair(keys[0], values[0],
                keys[1], values[1], target);
          return values[0];
        case LAST_VALUE:
          return null;
        case EXCEPTION:
          throw new IndexOutOfBoundsException(OUT_OF_RANGE);
      }
    }

    if (inRangeStrategy == null) {
      inRangeStrategy = EInRangeStrategy.INTERPOLATE;
    }

    switch (inRangeStrategy) {
      case PREVIOUS_VALUE:
        return values[index - 1];
      default:
        return InterpolationLibrary.interpolatePair(keys[index - 1],
            values[index - 1], keys[index], values[index], target);
    }
  }

}
