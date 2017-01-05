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

import com.opencsv.CSVReader;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.collections.MapUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt2matsim.lib.RouteShape;
import org.matsim.pt2matsim.lib.ShapedSchedule;
import org.matsim.pt2matsim.lib.ShapedTransitSchedule;
import org.matsim.pt2matsim.tools.*;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.pt2matsim.gtfs.lib.*;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.Map.Entry;


/**
 * Reads GTFS files and converts them to an unmapped MATSim Transit Schedule
 * <p/>
 * Based on GTFS2MATSimTransitSchedule by Sergio Ordonez
 *
 * @author polettif
 */
public class GtfsConverter implements GtfsFeed {

	private static final Logger log = Logger.getLogger(GtfsConverter.class);

	private boolean defaultAwaitDepartureTime = true;

	private LocalDate dateUsed = null;

	/**
	 * Path to the folder where the gtfs files are located
	 */
	private String root;

	/**
	 * whether the gtfs feed uses frequencies.txt or not
	 */
	private boolean usesFrequencies = false;

	/**
	 * whether the gtfs feed uses shapes or not
	 */
	private boolean usesShapes = false;

	/**
	 * The time format used in the output MATSim transit schedule
	 */
	private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

	/**
	 * The types of dates that will be represented by the new file
	 */
	private Set<String> serviceIdsToConvert = new HashSet<>();

	/**
	 * Set of service ids not defined in calendar.txt (only in calendar_dates.txt)
	 */
	private Set<String> serviceIdsNotInCalendarTxt = new HashSet<>();

	/**
	 * map for counting how many trips use each serviceId
	 */
	private Map<String, Integer> serviceIdsCount = new HashMap<>();



	// containers for storing gtfs data
	private Map<String, GtfsStop> gtfsStops = new HashMap<>();
	private Map<String, GtfsRoute> gtfsRoutes = new TreeMap<>();
	private Map<String, Service> services = new HashMap<>();
	private Map<Id<RouteShape>, RouteShape> shapes = new HashMap<>();
	private boolean warnStopTimes = true;

	private final CoordinateTransformation transformation;
	private ShapedTransitSchedule schedule = null;
	private Vehicles vhcls = null;

	public GtfsConverter(String gtfsFolder, String outputCoordinateSystem) {
		this.transformation = TransformationFactory.getCoordinateTransformation("WGS84", outputCoordinateSystem);
		loadFiles(gtfsFolder);
	}

	public GtfsConverter(String gtfsFolder) {
		this.transformation = new IdentityTransformation();
		loadFiles(gtfsFolder);
	}

	@Override
	public void convert(String serviceIdsParam) {
		convert(serviceIdsParam, ScheduleTools.createSchedule(), VehicleUtils.createVehiclesContainer());
	}

	@Override
	public void convert() {
		convert(ALL_SERVICE_IDS, ScheduleTools.createSchedule(), VehicleUtils.createVehiclesContainer());
	}

	/**
	 * Converts the loaded gtfs data to a matsim transit schedule
	 * <ol>
	 * <li>generate transitStopFacilities from gtfsStops</li>
	 * <li>Create a transitLine for each GtfsRoute</li>
	 * <li>Generate a transitRoute for each trip</li>
	 * <li>Get the stop sequence of the trip</li>
	 * <li>Calculate departures from stopTimes or frequencies</li>
	 * <li>add transitRoute to the transitLine and thus to the schedule</li>
	 * </ol>
	 */
	@Override
	public void convert(String serviceIdsParam, TransitSchedule transitSchedule, Vehicles vehicles) {
		getServiceIds(serviceIdsParam);

		this.schedule = new ShapedSchedule(transitSchedule);
		this.vhcls = vehicles;

		TransitScheduleFactory scheduleFactory = schedule.getFactory();

		log.info("Converting to MATSim transit schedule");

		int counterLines = 0;
		int counterRoutes = 0;

		/** [1]
		 * generating transitStopFacilities (mts) from gtfsStops and add them to the schedule.
		 * Coordinates are transformed here.
		 */
		for(Entry<String, GtfsStop> stopEntry : gtfsStops.entrySet()) {
			Coord result = transformation.transform(stopEntry.getValue().getPoint());
			TransitStopFacility stopFacility = scheduleFactory.createTransitStopFacility(Id.create(stopEntry.getKey(), TransitStopFacility.class), result, stopEntry.getValue().isBlocks());
			stopFacility.setName(stopEntry.getValue().getName());
			schedule.addStopFacility(stopFacility);
		}

		if(usesFrequencies) {
			log.info("    Using frequencies.txt to generate departures");
		} else {
			log.info("    Using stop_times.txt to generate departures");
		}

		DepartureIds departureIds = new DepartureIds();

		for(GtfsRoute gtfsRoute : gtfsRoutes.values()) {
			/** [2]
			 * Create a MTS transitLine for each GtfsRoute
			 */
			TransitLine transitLine = scheduleFactory.createTransitLine(Id.create(gtfsRoute.getShortName() + "_" + gtfsRoute.getRouteId(), TransitLine.class));
			schedule.addTransitLine(transitLine);
			counterLines++;

			/** [3]
			 * loop through each trip for the GTFSroute and generate transitRoute (if the serviceId is correct)
			 */
			for(Trip trip : gtfsRoute.getTrips().values()) {
				boolean isService = false;

				// if trip is part of used serviceId
				for(String serviceId : serviceIdsToConvert) {
					if(trip.getService().equals(services.get(serviceId))) {
						isService = true;
					}
				}

				if(isService) {
					/** [4]
					 * Get the stop sequence (with arrivalOffset and departureOffset) of the trip.
					 */
					List<TransitRouteStop> transitRouteStops = new ArrayList<>();
					Date startTime = trip.getStopTimes().get(trip.getStopTimes().firstKey()).getArrivalTime();
					for(StopTime stopTime : trip.getStopTimes().values()) {
						double arrival = Time.UNDEFINED_TIME, departure = Time.UNDEFINED_TIME;

						// add arrival time if current stopTime is not on the first stop of the route
						if(!stopTime.getSequencePosition().equals(trip.getStopTimes().firstKey())) {
							long difference = stopTime.getArrivalTime().getTime() - startTime.getTime();
							try {
								arrival = Time.parseTime(timeFormat.format(new Date(timeFormat.parse("00:00:00").getTime() + difference)));
							} catch (ParseException e) {
								e.printStackTrace();
							}
						}

						// add departure time if current stopTime is not on the last stop of the route
						if(!stopTime.getSequencePosition().equals(trip.getStopTimes().lastKey())) {
							long difference = stopTime.getDepartureTime().getTime() - startTime.getTime();
							try {
								departure = Time.parseTime(timeFormat.format(new Date(timeFormat.parse("00:00:00").getTime() + difference)));
							} catch (ParseException e) {
								e.printStackTrace();
							}
						}
						TransitRouteStop newTRS = scheduleFactory.createTransitRouteStop(schedule.getFacilities().get(Id.create(stopTime.getStopId(), TransitStopFacility.class)), arrival, departure);
						newTRS.setAwaitDepartureTime(defaultAwaitDepartureTime);
						transitRouteStops.add(newTRS);
					}

					/** [5.1]
					 * Calculate departures from frequencies (if available)
					 */
					TransitRoute transitRoute = null;
					if(usesFrequencies) {
						transitRoute = scheduleFactory.createTransitRoute(Id.create(trip.getId(), TransitRoute.class), null, transitRouteStops, gtfsRoute.getRouteType().name);

						for(Frequency frequency : trip.getFrequencies()) {
							for(Date actualTime = (Date) frequency.getStartTime().clone(); actualTime.before(frequency.getEndTime()); actualTime.setTime(actualTime.getTime() + frequency.getSecondsPerDeparture() * 1000)) {
								transitRoute.addDeparture(scheduleFactory.createDeparture(
										Id.create(departureIds.getNext(transitRoute.getId()), Departure.class),
										Time.parseTime(timeFormat.format(actualTime))));
							}
						}
						transitLine.addRoute(transitRoute);
						counterRoutes++;
					} else {
						/** [5.2]
						 * Calculate departures from stopTimes
						 */

						/* if stop sequence is already used by the same transitLine: just add new departure for the
						 * transitRoute that uses that stop sequence
						 */
						boolean routeExistsInTransitLine = false;
						for(TransitRoute currentTransitRoute : transitLine.getRoutes().values()) {
							if(currentTransitRoute.getStops().equals(transitRouteStops)) {
								currentTransitRoute.addDeparture(scheduleFactory.createDeparture(Id.create(departureIds.getNext(currentTransitRoute.getId()), Departure.class), Time.parseTime(timeFormat.format(startTime))));
								routeExistsInTransitLine = true;
								transitRoute = currentTransitRoute;
								break;
							}
						}

						/* if stop sequence is not used yet, create a new transitRoute (with transitRouteStops)
						 * and add the departure
						 */
						if(!routeExistsInTransitLine) {
							transitRoute = scheduleFactory.createTransitRoute(Id.create(trip.getId(), TransitRoute.class), null, transitRouteStops, gtfsRoute.getRouteType().name);

							transitRoute.addDeparture(scheduleFactory.createDeparture(Id.create(departureIds.getNext(transitRoute.getId()), Departure.class), Time.parseTime(timeFormat.format(startTime))));

							transitLine.addRoute(transitRoute);
							counterRoutes++;
						}
					}

					/* Save transit route (and line) for current shape */
					if(trip.hasShape()) {
						schedule.addShape(transitLine.getId(), transitRoute.getId(), trip.getShape());
					}
				}
			} // foreach trip
		} // foreach route

		/**
		 * Create default vehicles.
		 */
		ScheduleTools.createVehicles(schedule, vhcls);

		log.info("    Created " + counterRoutes + " routes on " + counterLines + " lines.");
		if(dateUsed != null) log.info("    Day " + dateUsed);
		log.info("... GTFS converted to an unmapped MATSIM Transit Schedule");
		log.info("#############################################################");
	}

	/**
	 * Calls all methods to load the gtfs files. Order is critical
	 */
	private void loadFiles(String inputPath) {
		if(!inputPath.endsWith("/")) inputPath += "/";
		this.root = inputPath;

		log.info("Loading GTFS files from " + root);
		try { loadStops(); } catch (IOException e) {
			throw new RuntimeException("File stops.txt not found!");
		}
		try { loadCalendar(); } catch (IOException e) {
			throw new RuntimeException("File calendar.txt not found! ");
		}
		loadCalendarDates();
		loadShapes();
		try { loadRoutes(); } catch (IOException e) {
			throw new RuntimeException("File routes.txt not found!");
		}
		try { loadTrips(); } catch (IOException e) {
			throw new RuntimeException("File trips.txt not found!");
		}
		try { loadStopTimes(); } catch (IOException e) {
			throw new RuntimeException("File stop_times.txt not found!");
		}
		loadFrequencies();
		log.info("All files loaded");
	}

	/**
	 * Reads all stops and puts them in {@link #gtfsStops}
	 * <p/>
	 * <br/><br/>
	 * stops.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Individual locations where vehicles pick up or drop off passengers.
	 *
	 * @throws IOException
	 */
	private void loadStops() throws IOException {
		log.info("Loading stops.txt");
		CSVReader reader;

		try {
			reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.STOPS.fileName));
			String[] header = reader.readNext(); // read header
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.STOPS.columns); // get column numbers for required fields

			String[] line = reader.readNext();
			while(line != null) {
				Coord coord = new Coord(Double.parseDouble(line[col.get(GtfsDefinitions.STOP_LON)]), Double.parseDouble(line[col.get(GtfsDefinitions.STOP_LAT)]));
				GtfsStop GtfsStop = new GtfsStop(coord, line[col.get(GtfsDefinitions.STOP_NAME)], false);
				gtfsStops.put(line[col.get(GtfsDefinitions.STOP_ID)], GtfsStop);

				line = reader.readNext();
			}

			reader.close();
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in stops.txt");
		}
		log.info("...     stops.txt loaded");
	}

	/**
	 * Reads all services and puts them in {@link #services}
	 * <p/>
	 * <br/><br/>
	 * calendar.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Dates for service IDs using a weekly schedule. Specify when service starts and ends,
	 * as well as days of the week where service is available.
	 *
	 * @throws IOException
	 */
	private void loadCalendar() throws IOException {
		log.info("Loading calendar.txt");
		try {
			CSVReader reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.CALENDAR.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.CALENDAR.columns);

			// assuming all days really do follow monday in the file
			int indexMonday = col.get("monday");

			String[] line = reader.readNext();
			int i = 1, c = 1;
			while(line != null) {
				if(i == Math.pow(2, c)) {
					log.info("        # " + i);
					c++;
				}
				i++;

				boolean[] days = new boolean[7];
				for(int d = 0; d < 7; d++) {
					days[d] = line[indexMonday + d].equals("1");
				}
				services.put(line[col.get(GtfsDefinitions.SERVICE_ID)], new Service(line[col.get(GtfsDefinitions.SERVICE_ID)], days, line[col.get(GtfsDefinitions.START_DATE)], line[col.get(GtfsDefinitions.END_DATE)]));

				line = reader.readNext();
			}

			reader.close();
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in calendar.txt");
		}
		log.info("...     calendar.txt loaded");
	}

	/**
	 * Adds service exceptions to {@link #services} (if available)
	 * <p/>
	 * <br/><br/>
	 * calendar_dates.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Exceptions for the service IDs defined in the calendar.txt file. If calendar_dates.txt includes ALL
	 * dates of service, this file may be specified instead of calendar.txt.
	 */
	private void loadCalendarDates() {
		// calendar dates are optional
		log.info("Looking for calendar_dates.txt");
		CSVReader reader;
		try {
			reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.CALENDAR_DATES.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.CALENDAR_DATES.columns);

			String[] line = reader.readNext();
			while(line != null) {
				Service currentService = services.get(line[col.get(GtfsDefinitions.SERVICE_ID)]);

				if(currentService == null) {
					currentService = new Service(
							line[col.get(GtfsDefinitions.SERVICE_ID)],
							new boolean[] { false, false, false, false, false, false, false },
							"19700101", "29991231"
					);

					services.put(currentService.getId(), currentService);

					if(serviceIdsNotInCalendarTxt.add(currentService.getId())) {
						log.warn("Service id \"" + currentService.getId() + "\" not defined in calendar.txt, only in calendar_dates.txt. Service id will still be used.");
					}
				}

				if(line[col.get(GtfsDefinitions.EXCEPTION_TYPE)].equals("2")) {
					currentService.addException(line[col.get(GtfsDefinitions.DATE)]);
				} else {
					currentService.addAddition(line[col.get(GtfsDefinitions.DATE)]);
				}

				line = reader.readNext();
			}
			reader.close();
			log.info("...     calendar_dates.txt loaded");
		} catch (IOException e) {
			log.info("...     no calendar dates file found.");
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in calendar_dates.txt");
		}
	}

	/**
	 * Loads shapes (if available) and puts them in {@link #shapes}. A shape is a sequence of points, i.e. a line.
	 * <p/>
	 * <br/><br/>
	 * shapes.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Rules for drawing lines on a map to represent a transit organization's routes.
	 */
	private void loadShapes() {
		// shapes are optional
		log.info("Looking for shapes.txt");
		CSVReader reader;
		try {
			reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.SHAPES.fileName));

			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.SHAPES.columns);

			String[] line = reader.readNext();
			while(line != null) {
				usesShapes = true; // shape file might exists but could be empty

				Id<RouteShape> shapeId = Id.create(line[col.get(GtfsDefinitions.SHAPE_ID)], RouteShape.class);

				RouteShape currentShape = shapes.get(shapeId);
				if(currentShape == null) {
					currentShape = new GtfsShape(line[col.get(GtfsDefinitions.SHAPE_ID)]);
					shapes.put(shapeId, currentShape);
				}
				Coord point = new Coord(Double.parseDouble(line[col.get(GtfsDefinitions.SHAPE_PT_LON)]), Double.parseDouble(line[col.get(GtfsDefinitions.SHAPE_PT_LAT)]));
				currentShape.addPoint(transformation.transform(point), Integer.parseInt(line[col.get(GtfsDefinitions.SHAPE_PT_SEQUENCE)]));
				line = reader.readNext();
			}
			reader.close();
			log.info("...     shapes.txt loaded");
		} catch (IOException e) {
			log.info("...     no shapes file found.");
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in shapes.txt");
		}
	}

	/**
	 * Basically just reads all routeIds and their corresponding names and types and puts them in {@link #gtfsRoutes}.
	 * <p/>
	 * <br/><br/>
	 * routes.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Transit routes. A route is a group of trips that are displayed to riders as a single service.
	 *
	 * @throws IOException
	 */
	private void loadRoutes() throws IOException {
		log.info("Loading routes.txt");
		try {
			CSVReader reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.ROUTES.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.ROUTES.columns);

			String[] line = reader.readNext();
			while(line != null) {
				int routeTypeNr = Integer.parseInt(line[col.get(GtfsDefinitions.ROUTE_TYPE)]);
				if(routeTypeNr < 0 || routeTypeNr > 7) {
					throw new RuntimeException("Invalid value for route type: " + routeTypeNr + " [https://developers.google.com/transit/gtfs/reference/routes-file]");
				}

				GtfsRoute newGtfsRoute = new GtfsRoute(line[col.get(GtfsDefinitions.ROUTE_ID)], line[col.get(GtfsDefinitions.ROUTE_SHORT_NAME)], GtfsDefinitions.RouteTypes.values()[routeTypeNr]);
				gtfsRoutes.put(line[col.get(GtfsDefinitions.ROUTE_ID)], newGtfsRoute);

				line = reader.readNext();
			}
			reader.close();
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in routes.txt");
		}
		log.info("...     routes.txt loaded");
	}

	/**
	 * Generates a trip with trip_id and adds it to the corresponding route (referenced by route_id) in {@link #gtfsRoutes}.
	 * Adds the shape_id as well (if shapes are used). Each trip uses one service_id, the serviceIds statistics are increased accordingly
	 * <p/>
	 * <br/><br/>
	 * trips.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Trips for each route. A trip is a sequence of two or more gtfsStops that occurs at specific time.
	 *
	 * @throws IOException
	 */
	private void loadTrips() throws IOException {
		log.info("Loading trips.txt");
		try {
			CSVReader reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.TRIPS.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.TRIPS.columns);

			String[] line = reader.readNext();
			while(line != null) {
				GtfsRoute gtfsRoute = gtfsRoutes.get(line[col.get("route_id")]);
				if(usesShapes) {
					Id<RouteShape> shapeId = Id.create(line[col.get(GtfsDefinitions.SHAPE_ID)], RouteShape.class);
					Trip newTrip = new Trip(line[col.get(GtfsDefinitions.TRIP_ID)], services.get(line[col.get(GtfsDefinitions.SERVICE_ID)]), shapes.get(shapeId), line[col.get(GtfsDefinitions.TRIP_ID)]);
					gtfsRoute.putTrip(line[col.get(GtfsDefinitions.TRIP_ID)], newTrip);
				} else {
					Trip newTrip = new Trip(line[col.get(GtfsDefinitions.TRIP_ID)], services.get(line[col.get(GtfsDefinitions.SERVICE_ID)]), null, line[col.get(GtfsDefinitions.TRIP_ID)]);
					gtfsRoute.putTrip(line[col.get(GtfsDefinitions.TRIP_ID)], newTrip);
				}

				// each trip uses one service id, increase statistics accordingly
				Integer count = MapUtils.getInteger(line[col.get(GtfsDefinitions.SERVICE_ID)], serviceIdsCount, 1);
				serviceIdsCount.put(line[col.get(GtfsDefinitions.SERVICE_ID)], count + 1);

				line = reader.readNext();
			}

			reader.close();
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in trips.txt");
		}
		log.info("...     trips.txt loaded");
	}

	/**
	 * Stop times are added to their respective trip (which are stored in {@link #gtfsRoutes}).
	 * <p/>
	 * <br/><br/>
	 * stop_times.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Times that a vehicle arrives at and departs from individual gtfsStops for each trip.
	 *
	 * @throws IOException
	 */
	private void loadStopTimes() throws IOException {
		log.info("Loading stop_times.txt");
		try {
			CSVReader reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.STOP_TIMES.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.STOP_TIMES.columns);

			String[] line = reader.readNext();
			int i = 1, c = 1;
			while(line != null) {
				if(i == Math.pow(2, c)) { log.info("        # " + i); c++; } i++; // just for logging so something happens in the console

				for(GtfsRoute currentGtfsRoute : gtfsRoutes.values()) {
					Trip trip = currentGtfsRoute.getTrips().get(line[col.get(GtfsDefinitions.TRIP_ID)]);
					if(trip != null) {
						try {
							if(!line[col.get(GtfsDefinitions.ARRIVAL_TIME)].equals("")) {
								trip.putStopTime(
									Integer.parseInt(line[col.get(GtfsDefinitions.STOP_SEQUENCE)]),
									new StopTime(Integer.parseInt(line[col.get(GtfsDefinitions.STOP_SEQUENCE)]),
											timeFormat.parse(line[col.get(GtfsDefinitions.ARRIVAL_TIME)]),
											timeFormat.parse(line[col.get(GtfsDefinitions.DEPARTURE_TIME)]),
											line[col.get(GtfsDefinitions.STOP_ID)]));
							}
							/** GTFS Reference: If this stop isn't a time point, use an empty string value for the
							 * arrival_time and departure_time fields.
							 */
							else {
								trip.putStop(Integer.parseInt(line[col.get(GtfsDefinitions.STOP_SEQUENCE)]), line[col.get(GtfsDefinitions.STOP_ID)]);
								if(warnStopTimes) {
									log.warn("No arrival time set! Stops without arrival times will be scheduled based on the " +
											"nearest preceding timed stop. This message is only given once.");
									warnStopTimes = false;
								}
							}
						} catch (NumberFormatException | ParseException e) {
							e.printStackTrace();
						}
					}
				}
				line = reader.readNext();
			}

			reader.close();
		} catch (ArrayIndexOutOfBoundsException i) {
			throw new RuntimeException("Emtpy line found in stop_times.txt");
		}
		log.info("...     stop_times.txt loaded");
	}

	/**
	 * Loads the frequencies (if available) and adds them to their respective trips in {@link #gtfsRoutes}.
	 * <p/>
	 * <br/><br/>
	 * frequencies.txt <i>[https://developers.google.com/transit/gtfs/reference]</i><br/>
	 * Headway (time between trips) for gtfsRoutes with variable frequency of service.
	 */
	private void loadFrequencies() {
		log.info("Looking for frequencies.txt");
		// frequencies are optional
		CSVReader reader;
		try {
			reader = new CSVReader(new FileReader(root + GtfsDefinitions.Files.FREQUENCIES.fileName));
			String[] header = reader.readNext();
			Map<String, Integer> col = getIndices(header, GtfsDefinitions.Files.FREQUENCIES.columns);

			String[] line = reader.readNext();
			while(line != null) {
				usesFrequencies = true;    // frequencies file might exists but could be empty

				for(GtfsRoute actualGtfsRoute : gtfsRoutes.values()) {
					Trip trip = actualGtfsRoute.getTrips().get(line[col.get(GtfsDefinitions.TRIP_ID)]);
					if(trip != null) {
						try {
							trip.addFrequency(new Frequency(timeFormat.parse(line[col.get(GtfsDefinitions.START_TIME)]), timeFormat.parse(line[col.get(GtfsDefinitions.END_TIME)]), Integer.parseInt(line[col.get(GtfsDefinitions.HEADWAY_SECS)])));
						} catch (NumberFormatException | ParseException e) {
							e.printStackTrace();
						}
					}
				}
				line = reader.readNext();
			}
			reader.close();
			log.info("...     frequencies.txt loaded");
		} catch (FileNotFoundException e) {
			log.info("...     no frequencies file found.");
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new RuntimeException("Emtpy line found in frequencies.txt");
		}
	}

	/**
	 * In case optional columns in a csv file are missing or are out of order, addressing array
	 * values directly via integer (i.e. where the column should be) does not work.
	 *
	 * @param header      the header (first line) of the csv file
	 * @param columnNames array of attributes you need the indices of
	 * @return the index for each attribute given in columnNames
	 */
	private static Map<String, Integer> getIndices(String[] header, String[] columnNames) {
		Map<String, Integer> indices = new HashMap<>();
		Set<String> notfound = new HashSet<>();

		for(String columnName : columnNames) {
			boolean found = false;
			for(int i = 0; i < header.length; i++) {
				if(header[i].equals(columnName)) {
					indices.put(columnName, i);
					found = true;
					break;
				}
			}
			if(!found) {
				notfound.add(columnName);
			}
		}

		if(notfound.size() > 0) {
			log.warn("Column name(s) "+notfound+" not found in csv. Might be some additional characters in the header or the encoding not being UTF-8.");
		}

		return indices;
	}


	/**
	 * sets the service id depending on the specified mode.
	 *
	 * @param param The date for which all service ids should be looked up.
	 *              Or the algorithm with which you want to get the service ids.
	 */
	private void getServiceIds(String param) {
		switch (param) {
			case ALL_SERVICE_IDS:
				log.warn("    Using all trips is not recommended");
				log.info("... Using all service IDs");
				this.serviceIdsToConvert = services.keySet();
				break;

			case DAY_WITH_MOST_SERVICES: {
				for(Entry<LocalDate, Set<String>> e : Service.dateStats.entrySet()) {
					try {
						if(e.getValue().size() > serviceIdsToConvert.size()) {
							this.serviceIdsToConvert = e.getValue();
							dateUsed = e.getKey();
						}
					} catch (Exception e1) {
						e1.printStackTrace();
					}
				}
				log.info("... Using service IDs of the day with the most services (" + DAY_WITH_MOST_SERVICES + ").");
				log.info("    " + serviceIdsToConvert.size() + " services on " + dateUsed);
				break;
			}

			case DAY_WITH_MOST_TRIPS: {
				int maxTrips = 0;
				for(Entry<LocalDate, Set<String>> e : Service.dateStats.entrySet()) {
					int nTrips = 0;
					for(String s : e.getValue()) {
						nTrips += serviceIdsCount.get(s);
					}
					if(nTrips > maxTrips) {
						maxTrips = nTrips;
						this.serviceIdsToConvert = e.getValue();
						dateUsed = e.getKey();
					}
				}
				log.info("... Using service IDs of the day with the most trips (" + DAY_WITH_MOST_TRIPS + ").");
				log.info("    " + maxTrips + " trips and " + serviceIdsToConvert.size() + " services on " + dateUsed);
				break;
			}

			default:
				try {
					dateUsed = LocalDate.of(Integer.parseInt(param.substring(0, 4)), Integer.parseInt(param.substring(4, 6)), Integer.parseInt(param.substring(6, 8)));
					this.serviceIdsToConvert = getServiceIdsOnDate(dateUsed);
					log.info("        Using service IDs on " + param + ": " + this.serviceIdsToConvert.size() + " services.");
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Service id param not recognized! Allowed: day in format \"yyyymmdd\", " + DAY_WITH_MOST_SERVICES + ", "+ DAY_WITH_MOST_TRIPS + ", " + ALL_SERVICE_IDS);
				}
				break;
		}
	}

	private Set<String> getServiceIdsOnDate(LocalDate checkDate) {
		HashSet<String> idsOnCheckDate = new HashSet<>();
		for(Service service : services.values()) {
			if(dateIsOnService(checkDate, service)) {
				idsOnCheckDate.add(service.getId());
			}
		}
		return idsOnCheckDate;
	}

	/**
	 * @return <code>true</code> if the given date is used by the given service.
	 */
	private boolean dateIsOnService(LocalDate checkDate, Service service) {
		// check if checkDate is an addition
		if(service.getAdditions().contains(checkDate)) {
			return true;
		}
		if(checkDate.isBefore(service.getEndDate()) && checkDate.isAfter(service.getStartDate())) {
			// check if the checkDate is not an exception of the service
			if(service.getExceptions().contains(checkDate)) {
				return false;
			}
			// get weekday (0 = monday)
			int weekday = checkDate.getDayOfWeek().getValue() - 1;
			return service.getDays()[weekday];
		}
		return false;
	}

	@Override
	public Map<Id<RouteShape>, RouteShape> getShapes() {
		return shapes;
	}


	public Map<String, GtfsRoute> getGtfsRoutes() {
		return gtfsRoutes;
	}

	/**
	 * helper class for meaningful departureIds
	 */
	private class DepartureIds {

		private Map<Id<TransitRoute>, Integer> ids = new HashMap<>();

		String getNext(Id<TransitRoute> transitRouteId) {
			if(!ids.containsKey(transitRouteId)) {
				ids.put(transitRouteId, 1);
				return transitRouteId + "_01";
			} else {
				int i = ids.put(transitRouteId, ids.get(transitRouteId) + 1) + 1;
				return transitRouteId + "_" + String.format("%03d", i);
			}

		}
	}

	public ShapedTransitSchedule getShapedTransitSchedule() {
		return schedule;
	}

	public TransitSchedule getSchedule() {
		return schedule;
	}

	public Vehicles getVehicles() {
		return vhcls;
	}


}