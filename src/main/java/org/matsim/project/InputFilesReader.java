package org.matsim.project;

import byu.edu.activitysimutils.ActivitySimFacilitiesReader;
import byu.edu.activitysimutils.ActivitySimPersonsReader;
import byu.edu.activitysimutils.ActivitySimTripsReader;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.File;

public class InputFilesReader {
    private static final Logger log = Logger.getLogger(InputFilesReader.class);
    private Scenario scenario;

    public InputFilesReader(Scenario sc){
        this.scenario = sc;
    }

    /**
     * Read the ActivitySim output files into the scenario
     * @param personsFile Path to activitysim output persons file
     * @param tripsFile Path to activitysim output trips file
     */
    public void readActivitySimFiles(File personsFile, File tripsFile,
                                     File facilitiesFile, File householdsFile,
                                     File householdCoordFile){
        ActivitySimFacilitiesReader facilitiesReader = new ActivitySimFacilitiesReader(scenario, facilitiesFile,
                householdsFile, householdCoordFile);
        facilitiesReader.readFacilities();
        facilitiesReader.readHouseholds();
        ActivitySimPersonsReader personsReader = new ActivitySimPersonsReader(scenario, personsFile);
        personsReader.readPersons();
        ActivitySimTripsReader tripsReader = new ActivitySimTripsReader(scenario, tripsFile,
                facilitiesReader.getTazFacilityMap());
        tripsReader.readTrips();
        personsReader.readPlans();

    }


    public static void main(String[] args){
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        scenario.getConfig().global().setCoordinateSystem("EPSG:26912");
        InputFilesReader reader = new InputFilesReader(scenario);
        // String scenarioPath = args[0];
        String allPath = "../beam/activitysim_output/";
        String scenarioPath = allPath + "SampleFiles_50-3/";
        String outputFileName = "plans_50-3.xml";

        //File personsFile = new File(scenarioPath + args[1]);
        File personsFile = new File(scenarioPath + "persons.csv");
        File tripsFile = new File(scenarioPath + "trips.csv");
        File householdsFile = new File(scenarioPath + "households.csv");
        File facilitiesFile = new File(allPath + "facility_ids.csv");
        File householdCoordFile = new File(allPath + "hhcoord.csv");
        reader.readActivitySimFiles(personsFile, tripsFile, facilitiesFile, householdsFile, householdCoordFile);

        new PopulationWriter(scenario.getPopulation()).write(scenarioPath + outputFileName);
        new PopulationWriter(scenario.getPopulation()).write(scenarioPath + outputFileName);
        // new FacilitiesWriter(scenario.getActivityFacilities()).write(scenarioPath + "facilities.xml.gz");
    }

}
