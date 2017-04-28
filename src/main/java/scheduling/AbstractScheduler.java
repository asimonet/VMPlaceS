package scheduling;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import simulation.SimulatorManager;
import trace.Trace;

import java.util.*;

/**
 * Abstract scheduler that must be extended by implemented algorithms.
 */
public abstract class AbstractScheduler implements Scheduler {

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Fields //////////////////////////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
     * Indicates if the reconfiguration plan has been aborted
     */
    protected boolean rpAborted;

    /**
     * The depth of the graph of the reconfiguration actions.
     */
	protected int planGraphDepth;

    /**
     * Id to serialize configuration and reconfiguration into a particular file name.
     */
    protected int id;

    /**
     * Number of ongoing migrations.
     */
	private volatile int ongoingMigrations = 0 ;

    private Collection<XVM> currentMigrations;

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Constructors ////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
	 * Constructor initializing fields and creating the source configuration regarding xHosts.
	 */
	protected AbstractScheduler() {
		planGraphDepth = 0;
        rpAborted = false;
        currentMigrations = new HashSet<XVM>();
	}

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Abstract methods ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Applies the reconfiguration plan from source model to dest model.
     */
    protected abstract void applyReconfigurationPlan();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Implemented methods /////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Incs the number of ongoing migrations.
     */
	private void incOngoingMigrations(){
        this.ongoingMigrations++ ;
        Trace.hostVariableAdd(SimulatorManager.getInjectorNodeName(), "NB_MIG", 1);
    }

    /**
     * Decs the number of ongoing migrations.
     */
	private void decOngoingMigrations() {
        this.ongoingMigrations-- ;
    }

    /**
     * Indicates if there are still some ongoing migrations.
     * @return true or false
     */
	protected boolean ongoingMigrations() {
        return (this.ongoingMigrations != 0);
    }

    protected Collection<XVM> getMigratingVMs() {
        return currentMigrations;
    }

    /**
     * Core implementation
     *
     * @param hostsToCheck The XHost to check for violations
     * @return A SchedulerResult representing the success or failure of the algorithm loop
     */
    public SchedulerResult checkAndReconfigure(Collection<XHost> hostsToCheck) {
         Msg.info("Launching scheduler (id = " + id + ") - start to compute");

        ComputingResult computingResult;
        double reconfigurationTime;
        SchedulerResult enRes = new SchedulerResult();

		/* Tracing code */
        for (XHost h : hostsToCheck) {
            if (!h.isViable())
                Trace.hostPushState(h.getName(), "PM", "violation-det");
            Trace.hostSetState(h.getName(), "SERVICE", "booked");
        }

        /** PLEASE NOTE THAT ALL COMPUTATIONS BELOW DOES NOT MOVE FORWARD THE MSG CLOCK ***/
        computingResult = this.computeReconfigurationPlan();

        /* Tracing code */
        double computationTimeAsDouble = computingResult.duration / 1000;

        int partitionSize = hostsToCheck.size();

        /** **** NOW LET'S GO BACK TO THE SIMGRID WORLD **** */

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "compute", String.format(Locale.US, "{\"duration\" : %f, \"state\" : \"%s\", \"migration_count\": %d, \"psize\": %d}", computationTimeAsDouble, computingResult.state, computingResult.nbMigrations, partitionSize));


        try {
            Process.getCurrentProcess().waitFor(computingResult.duration / 1000); // instead of waitFor that takes into account only seconds
        } catch (HostFailureException e) {
            e.printStackTrace();
        }

        Msg.info("Computation time (in ms):" + computingResult.duration);
        enRes.duration = computingResult.duration;

        if (computingResult.state.equals(ComputingResult.State.NO_RECONFIGURATION_NEEDED)) {
            Msg.info("Configuration remains unchanged");
            enRes.state = SchedulerResult.State.NO_RECONFIGURATION_NEEDED;
        } else if (computingResult.state.equals(ComputingResult.State.SUCCESS)) {

			/* Tracing code */
            for (XHost h : hostsToCheck)
                Trace.hostSetState(h.getName(), "SERVICE", "reconfigure");

            Trace.hostPushState(Host.currentHost().getName(), "SERVICE", "reconfigure");

            // Applying reconfiguration plan
            Msg.info("Starting reconfiguration");
            double startReconfigurationTime = Msg.getClock();
            this.applyReconfigurationPlan();
            double endReconfigurationTime = Msg.getClock();
            reconfigurationTime = (endReconfigurationTime - startReconfigurationTime);
            Msg.info("Reconfiguration time (in ms): " + reconfigurationTime * 1000);
            enRes.duration += reconfigurationTime * 1000;
            Msg.info("Number of nodes used: " + hostsToCheck.size());

            enRes.state = this.rpAborted ?
                    SchedulerResult.State.RECONFIGURATION_PLAN_ABORTED : SchedulerResult.State.SUCCESS;

            Trace.hostPopState(Host.currentHost().getName(), "SERVICE"); //PoP reconfigure;
        } else {
            Msg.info(SimulatorProperties.getAlgo() + "-"+ SimulatorProperties.getImplementation() +" did not find any viable solution");
            enRes.state = SchedulerResult.State.NO_VIABLE_CONFIGURATION;
        }

        if(SimulatorProperties.getHostsTurnoff()) {
            for (XHost host : SimulatorManager.getSGTurnOnHostingHosts()) {
                if (host.getRunnings().size() == 0)
                    SimulatorManager.turnOff(host);
            }
        }

        SimulatorManager.writeCurrentConfiguration();

		/* Tracing code */
        for (XHost h : hostsToCheck)
            Trace.hostSetState(h.getName(), "SERVICE", "free");

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "free");
        return enRes;
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Common VM management methods ////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Relocates a VM.
     * @param vmName vm name
     * @param sourceName source configuration name
     * @param destName destination configuration name
     */
    public void relocateVM(final String vmName, final String sourceName, final String destName) {

        Random rand = new Random(SimulatorProperties.getSeed());

        Msg.info("Relocate VM " + vmName + " (from " + sourceName + " to " + destName + ")");

        if (destName != null) {
            String[] args = new String[3];

            args[0] = vmName;
            args[1] = sourceName;
            args[2] = destName;
            // Asynchronous migration
            // The process is launched on the source node
            try {
                new org.simgrid.msg.Process(Host.getByName(sourceName), "Migrate-"+ vmName+"-"+ rand.nextDouble(), args) {
                    public void main(String[] args) {
                        XHost destHost = null;
                        XHost sourceHost = null;

                        try {
                            sourceHost = SimulatorManager.getXHostByName(args[1]);
                            destHost = SimulatorManager.getXHostByName(args[2]);
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("You are trying to migrate from/to a non existing node");
                        }

                        if (destHost != null) {
                            if (!sourceHost.isOff()) {
                                incOngoingMigrations();
                                currentMigrations.add(SimulatorManager.getXVMByName(args[0]));
                                if(SimulatorManager.migrateVM(args[0], args[1], args[2]) == false) //If Migration is not correctly performed
                                    rpAborted = true;
                                decOngoingMigrations();
                                currentMigrations.remove(SimulatorManager.getXVMByName(args[0]));
                            }
                        }
                    }
                }.start();

            } catch (Exception e) {
                e.printStackTrace();
            }

        } else {
            System.err.println("You are trying to relocate a VM on a non existing node");
            System.exit(-1);
        }
    }



    /**
     * Suspends a VM.
     * @param vmName vm name
     * @param hostName host name
     */
/*    public void suspendVM(final String vmName, final String hostName) {

        if(!SimulatorManager.suspendVM(vmName, hostName))
            rpAborted=true;

    }
*/
    /**
     * Resumes a VM.
     * @param vmName vm name
     * @param hostName host name
     */
/*    public void resumeVM(final String vmName, final String hostName) {

        if (!SimulatorManager.resumeVM(vmName, hostName))
            rpAborted = true;
    }
*/

}
