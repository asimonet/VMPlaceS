package scheduling.centralized.entropy2;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import entropy.configuration.*;
import entropy.configuration.parser.FileConfigurationSerializerFactory;
import entropy.execution.Dependencies;
import entropy.execution.TimedExecutionGraph;
import entropy.plan.PlanException;
import entropy.plan.TimedReconfigurationPlan;
import entropy.plan.action.Action;
import entropy.plan.action.Migration;
import entropy.plan.choco.ChocoCustomRP;
import entropy.plan.durationEvaluator.MockDurationEvaluator;
import entropy.vjob.DefaultVJob;
import entropy.vjob.VJob;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Process;
import scheduling.AbstractScheduler;
import scheduling.SchedulerRes;
import simulation.SimulatorManager;
import trace.Trace;

import java.io.*;
import java.util.*;

public class Entropy2RP extends AbstractScheduler<Configuration, TimedReconfigurationPlan> {

    private ChocoCustomRP planner;

    public Entropy2RP(Collection<XHost> xhosts) {
        super(xhosts);
        this.id = new Random().nextInt();
        this.source = this.extractConfiguration(xhosts);
    }

    public Entropy2RP(Collection<XHost> xhosts, Integer id) {
		super(xhosts);
        this.source = this.extractConfiguration(xhosts);
		planner =  new ChocoCustomRP(new MockDurationEvaluator(2, 5, 1, 1, 7, 14, 7, 2, 4));//Entropy2.1
		planner.setRepairMode(true); //true by default for ChocoCustomRP/Entropy2.1; false by default for ChocoCustomPowerRP/Entrop2.0
        planner.setTimeLimit(source.getAllNodes().size()/8);
        this.id = id;
        super.isRPAborted = false;
        //Log the current Configuration
        try {
            String fileName = "logs/entropy/configuration/" + id + "-"+ System.currentTimeMillis() + ".txt";
            /*File file = new File("logs/entropy/configuration/" + id + "-"+ System.currentTimeMillis() + ".txt");
            file.getParentFile().mkdirs();
            PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
            pw.write(source.toString());
            pw.flush();
            pw.close();*/
            FileConfigurationSerializerFactory.getInstance().write(source, fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }

	}

    public ComputingState computeReconfigurationPlan() {
		ComputingState res = ComputingState.SUCCESS;
		
		// All VMs are encapsulated into the same vjob for the moment - Adrien, Nov 18 2011
		List<VJob> vjobs = new ArrayList<>();
		VJob v = new DefaultVJob("v1");//Entropy2.1
//		VJob v = new BasicVJob("v1");//Entropy2.0
		/*for(VirtualMachine vm : source.getRunnings()){
			v.addVirtualMachine(vm);
		}
		
		for(Node n : source.getAllNodes()){
			n.setPowerBase(100);
			n.setPowerMax(200);
		}*///Entropy2.0 Power
		v.addVirtualMachines(source.getRunnings());//Entropy2.1
		vjobs.add(v);
		try {
			timeToComputeVMRP = System.currentTimeMillis();
			reconfigurationPlan = planner.compute(source,
                    source.getRunnings(),
                    source.getWaitings(),
                    source.getSleepings(),
                    new SimpleManagedElementSet<VirtualMachine>(),
                    source.getOnlines(),
                    source.getOfflines(), vjobs);
			timeToComputeVMRP = System.currentTimeMillis() - timeToComputeVMRP;
		} catch (PlanException e) {
			e.printStackTrace();
			res = ComputingState.RECONFIGURATION_FAILED ;
			timeToComputeVMRP = System.currentTimeMillis() - timeToComputeVMRP;
			reconfigurationPlan = null;
		}
		
		if(reconfigurationPlan != null){
			if(reconfigurationPlan.getActions().isEmpty())
				res = ComputingState.NO_RECONFIGURATION_NEEDED;
			
			reconfigurationPlanCost = reconfigurationPlan.getDuration();
			destination = reconfigurationPlan.getDestination();
			nbMigrations = computeNbMigrations();
			reconfigurationGraphDepth = computeReconfigurationGraphDepth();
		}
		
		return res; 
	}
		
	//Get the number of migrations
	private int computeNbMigrations(){
		int nbMigrations = 0;

		for (Action a : reconfigurationPlan.getActions()){
			if(a instanceof Migration){
				nbMigrations++;
			}
		}
		
		return nbMigrations;
	}
	
	//Get the depth of the reconfiguration graph
	//May be compared to the number of steps in Entropy 1.1.1
	//Return 0 if there is no action, and (1 + maximum number of dependencies) otherwise
	private int computeReconfigurationGraphDepth(){
		if(reconfigurationPlan.getActions().isEmpty()){
			return 0;
		}
		
		else{
			int maxNbDeps = 0;
			TimedExecutionGraph g = reconfigurationPlan.extractExecutionGraph();
			int nbDeps;
	
			//Set the reverse dependencies map
			for (Dependencies dep : g.extractDependencies()) {
				nbDeps = dep.getUnsatisfiedDependencies().size();
				
				if (nbDeps > maxNbDeps)
					maxNbDeps = nbDeps;
			}
	
			return 1 + maxNbDeps;
		}
	}
	
	public void applyReconfigurationPlan() {
		if(reconfigurationPlan != null && !reconfigurationPlan.getActions().isEmpty()){
			//Log the reconfiguration plan
            // Flavien / Adrien - In order to prevent random iterations due to the type of reconfiguration Plan (i.e. HashSet see Javadoc)
            LinkedList<Action> sortedActions = new LinkedList<Action>(reconfigurationPlan.getActions());
            Collections.sort(sortedActions, new Comparator<Action>() {
                @Override
                public int compare(Action a1, Action a2) {
                    if ((a1 instanceof Migration) && (a2 instanceof Migration)){
                        return ((Migration)a1).getVirtualMachine().getName().compareTo(((Migration)a2).getVirtualMachine().getName());
                    }
                    return 0;  //To change body of implemented methods use File | Settings | File Templates.
                }
            });


            try {
                File file = new File("logs/entropy/reconfigurationplan/" + id + "-" + System.currentTimeMillis() + ".txt");
                file.getParentFile().mkdirs();
                PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
                //pw.write(reconfigurationPlan.toString());
                for (Action a : sortedActions) {
                    pw.write(a.toString()+"\n");
                }
                pw.flush();
                pw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Apply the reconfiguration plan.
			try {
				applyReconfigurationPlanLogically(sortedActions);
			} catch (InterruptedException e) {
				e.printStackTrace();
			} 
		}
	}


    //Apply the reconfiguration plan logically (i.e. create/delete Java objects)
    private void applyReconfigurationPlanLogically(LinkedList<Action> sortedActions) throws InterruptedException{
        Map<Action, List<Dependencies>> revDependencies = new HashMap<Action, List<Dependencies>>();
        TimedExecutionGraph g = reconfigurationPlan.extractExecutionGraph();

        //Set the reverse dependencies map
        for (Dependencies dep : g.extractDependencies()) {
            for (Action a : dep.getUnsatisfiedDependencies()) {
                if (!revDependencies.containsKey(a)) {
                    revDependencies.put(a, new LinkedList<Dependencies>());
                }
                revDependencies.get(a).add(dep);
            }
        }

        //Start the feasible actions
        // ie, actions with a start moment equals to 0.
        for (Action a : sortedActions) {
            if ((a.getStartMoment() == 0)  && !this.isRPAborted) {
                instantiateAndStart(a);
            }

            if (revDependencies.containsKey(a)) {
                //Get the associated depenencies and update it
                for (Dependencies dep : revDependencies.get(a)) {
                    dep.removeDependency(a);
                    //Launch new feasible actions.
                    if (dep.isFeasible() && !this.isRPAborted) {
                        instantiateAndStart(dep.getAction());
                    }
                }
            }
        }

        // If you reach that line, it means that either the execution of the plan has been completely launched or the
        // plan has been aborted. In both cases, we should wait for the completion of on-going migrations

        // Add a watch dog to determine infinite loop
        int watchDog = 0;

        while(this.ongoingMigration()){
        //while(this.ongoingMigration() && !SimulatorManager.isEndOfInjection()){
            try {
                org.simgrid.msg.Process.getCurrentProcess().waitFor(1);
                watchDog ++;
                if (watchDog%100==0){
                  Msg.info("You're are waiting for a couple of seconds (already "+watchDog+" seconds)");
                    if(SimulatorManager.isEndOfInjection()){
                        Msg.info("Something wrong we are waiting too much, bye bye");
                        System.exit(-1);
                    }
                }
            } catch (HostFailureException e) {
                e.printStackTrace();
            }
        }
    }


    private void instantiateAndStart(Action a) throws InterruptedException{
        if(a instanceof Migration){
            Migration migration = (Migration)a;
            this.relocateVM(migration.getVirtualMachine().getName(), migration.getHost().getName(), migration.getDestination().getName());
        } else{
            System.err.println("UNRECOGNIZED ACTION WHEN APPLYING THE RECONFIGURATION PLAN");
        }
    }

    /**
     * @param hostsToCheck
     * @return the duration of the reconfiguration (i.e. > 0), -1 there is no viable reconfiguration, -2 the reconfiguration crash
     */
    public SchedulerRes checkAndReconfigure(Collection<XHost> hostsToCheck) {

        long beginTimeOfCompute;
        long endTimeOfCompute;
        long computationTime;
        ComputingState computingState;
        long reconfigurationTime = 0;
        SchedulerRes enRes = new SchedulerRes();

		/* Tracing code */
        int i;
        for (XHost h : hostsToCheck) {
            if (!h.isViable())
                Trace.hostPushState(h.getName(), "PM", "violation-det");
            Trace.hostSetState(h.getName(), "SERVICE", "booked");
        }

        Msg.info("Launching scheduler (id = " + id + ") - start to compute");
        Msg.info("Nodes considered: " + source.getAllNodes().toString());

        /** PLEASE NOTE THAT ALL COMPUTATIONS BELOW DOES NOT MOVE FORWARD THE MSG CLOCK ***/
        beginTimeOfCompute = System.currentTimeMillis();
        computingState = this.computeReconfigurationPlan();
        endTimeOfCompute = System.currentTimeMillis();
        computationTime = (endTimeOfCompute - beginTimeOfCompute);

        /* Tracing code */
        double computationTimeAsDouble = ((double) computationTime) / 1000;

        int migrationCount = 0;
        if(computingState.equals(ComputingState.SUCCESS)) {
            migrationCount = this.reconfigurationPlan.size();
        }

        int partitionSize = hostsToCheck.size();

        /** **** NOW LET'S GO BACK TO THE SIMGRID WORLD **** */

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "compute", String.format("{\"duration\" : %f, \"result\" : \"%s\", \"migration_count\": %d, \"psize\": %d}", computationTimeAsDouble, computingState, migrationCount, partitionSize));


        try {
            org.simgrid.msg.Process.sleep(computationTime); // instead of waitFor that takes into account only seconds
        } catch (HostFailureException e) {
            e.printStackTrace();
        }

        Msg.info("Computation time (in ms):" + computationTime);
        enRes.setDuration(computationTime);

        if (computingState.equals(ComputingState.NO_RECONFIGURATION_NEEDED)) {
            Msg.info("Configuration remains unchanged"); //res is already set to 0.
        } else if (computingState.equals(ComputingState.SUCCESS)) {

			/* Tracing code */
            // TODO Adrien -> Adrien, try to consider only the nodes that are impacted by the reconfiguration plan
            for (XHost h : hostsToCheck)
                Trace.hostSetState(h.getName(), "SERVICE", "reconfigure");

            Trace.hostPushState(Host.currentHost().getName(), "SERVICE", "reconfigure");


            Msg.info("Starting reconfiguration");
            double startReconfigurationTime = Msg.getClock();
            this.applyReconfigurationPlan();
            double endReconfigurationTime = Msg.getClock();
            reconfigurationTime = ((long) (endReconfigurationTime - startReconfigurationTime) * 1000);
            Msg.info("Reconfiguration time (in ms): " + reconfigurationTime);
            enRes.setDuration(enRes.getDuration() + reconfigurationTime);
            Msg.info("Number of nodes used: " + hostsToCheck.size());
            if (this.isRPAborted)
                enRes.setRes(-2);
            else
                enRes.setRes(1);

            Trace.hostPopState(Host.currentHost().getName(), "SERVICE"); //PoP reconfigure;
        } else {
            Msg.info("Entropy did not find any viable solution");
            enRes.setRes(-1);
        }

		/* Tracing code */
        for (XHost h : hostsToCheck)
            Trace.hostSetState(h.getName(), "SERVICE", "free");

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "free");
        return enRes;
    }

    // Create configuration for Entropy
    protected Configuration extractConfiguration(Collection<XHost> xhosts) {
        Configuration currConf = new SimpleConfiguration();

        // Add nodes
        for (XHost tmpH:xhosts){
            // Consider only hosts that are turned on
            if (tmpH.isOff()) {
                System.err.println("WTF, you are asking me to analyze a dead node (" + tmpH.getName() + ")");
                //System.exit(-1);
            }

            Node tmpENode = new SimpleNode(tmpH.getName(), tmpH.getNbCores(), tmpH.getCPUCapacity(), tmpH.getMemSize());
            currConf.addOnline(tmpENode);
            for (XVM tmpVM : tmpH.getRunnings()) {
                currConf.setRunOn(new SimpleVirtualMachine(tmpVM.getName(), (int) tmpVM.getCoreNumber(), 0,
                                tmpVM.getMemSize(), (int) tmpVM.getCPUDemand(), tmpVM.getMemSize()),
                        tmpENode
                );
            }

        }

        return currConf;
    }


}
