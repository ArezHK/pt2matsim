/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.pt2matsim.gtfs;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt2matsim.tools.RouteShape;
import org.matsim.pt2matsim.tools.ShapedTransitSchedule;
import org.matsim.vehicles.Vehicles;

import java.util.Map;

/**
 * An interface to load and convert GTFS feeds
 *
 * @author polettif
 */
public interface GtfsFeed {

	void convert(String serviceIdsParam, TransitSchedule transitSchedule, Vehicles vehicles);

	void convert(String serviceIdsParam);

	void convert();

	TransitSchedule getSchedule();

	Vehicles getVehicles();

	ShapedTransitSchedule getShapedSchedule();

	void writeRouteShapeReferenceFile(String filename);
}