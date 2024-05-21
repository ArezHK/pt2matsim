package org.matsim.pt2matsim.run.TuMunchenL7;

import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.gtfs.GtfsConverter;
import org.matsim.pt2matsim.run.Gtfs2TransitSchedule;

public class CreateSchedule {

    public static void main(String[] args) {
        Gtfs2TransitSchedule.run("SWU", GtfsConverter.DAY_WITH_MOST_SERVICES, TransformationFactory.DHDN_GK4, "ulmSchedule.xml", "ulmVehicles.xml");
    }
}
