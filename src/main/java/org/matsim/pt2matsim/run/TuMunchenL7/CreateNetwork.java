package org.matsim.pt2matsim.run.TuMunchenL7;

import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.run.Osm2MultimodalNetwork;

public class CreateNetwork {

    public static void main(String[] args) {
        Osm2MultimodalNetwork.run("ulm.osm", "ulmMultiModel.xml", TransformationFactory.DHDN_GK4);
    }
}
