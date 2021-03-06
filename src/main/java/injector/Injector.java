package injector;
import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.NativeException;
import org.simgrid.msg.Process;
import scheduling.hierarchical.snooze.SnoozeProperties;
import trace.Trace;

import scheduling.centralized.entropy2.EntropyProperties;
import simulation.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;


public class Injector extends Process {

    private Deque<InjectorEvent> evtQueue = null ;
    private Deque<LoadEvent> loadQueue = null ;
    private Deque<FaultEvent> faultQueue = null ;

	Injector(Host host, String name, String[] args) throws HostNotFoundException, NativeException  {
	    super(host, name, args);
       // System.out.println("Create the event queues");
        loadQueue = generateLoadQueue(SimulatorManager.getSGVMs().toArray(new XVM[SimulatorManager.getSGVMs().size()]), SimulatorProperties.getDuration(), SimulatorProperties.getLoadPeriod());
        //System.out.println("Size of getCPUDemand queue:"+loadQueue.size());
        // Stupid code to stress Snooze service nodes - Used for the paper submission
        if(SimulatorProperties.getAlgo().equals("hierarchical") && SnoozeProperties.faultMode())
            faultQueue =generateSnoozeFaultQueue(SimulatorManager.getSGHostsToArray(), SimulatorProperties.getDuration());
        else
            faultQueue =generateFaultQueue(SimulatorManager.getSGHostsToArray(), SimulatorProperties.getDuration(), SimulatorProperties.getCrashPeriod());
         System.out.println("Size of fault queue:"+faultQueue.size());
        evtQueue = mergeQueues(loadQueue,faultQueue);
       // System.out.println("Size of event queue:"+evtQueue.size());

        // Serialize eventqueue in a file.
        File f = new File ("injector_queue.txt");
        try
        {
            FileWriter fw = new FileWriter (f);

            for (InjectorEvent evt: evtQueue)
                fw.write (evt.toString()+"\n");
            fw.close();
        }
        catch (IOException exception)
        {
            System.out.println ("Erreur lors de la lecture : " + exception.getMessage());
        }
    }


    /**
     *
     * @param vms, Simgrid VMs that have been instanciated
     * @param duration int, duration of the simulated time in second
     * @param injectionPeriod int,  frequency of event occurrence in seconds
     * @return the queue of the VM changes
     */
    public static Deque<LoadEvent> generateLoadQueue(XVM[] vms, long duration, int injectionPeriod) {

        LinkedList<LoadEvent> eventQueue = new LinkedList<LoadEvent>();
        Random randExpDis=new Random(SimulatorProperties.getSeed());
        double currentTime = 0 ;
        double lambdaPerVM=1.0/injectionPeriod ; // Nb Evt per VM (average)

        Random randGaussian=new Random(SimulatorProperties.getSeed());

        double mean = SimulatorProperties.getMeanLoad();
        double sigma = SimulatorProperties.getStandardDeviationLoad();

        double gLoad = 0;

        double lambda=lambdaPerVM*vms.length;

       // int maxCPUDemand = SimulatorProperties.getCPUCapacity()/SimulatorProperties.getNbOfCPUs();
        int maxCPUDemand = SimulatorProperties.getVMMAXCPUConsumption();
        int nbOfCPUDemandSlots = SimulatorProperties.getNbOfCPUConsumptionSlots();
        int vmCPUDemand;
        long id=0;
        XVM tempVM;

        currentTime+=exponentialDis(randExpDis, lambda);

        Random randVMPicker = new Random(SimulatorProperties.getSeed());
        int nbOfVMs = vms.length;

        while(currentTime < duration){
            //   if( !skipOverlappingEvent || ((int)currentTime) % EntropyProperties.getEntropyPeriodicity() != 0){
            // select a VM
            tempVM = vms[randVMPicker.nextInt(nbOfVMs)];
            // and change its state

            int cpuConsumptionSlot = maxCPUDemand/nbOfCPUDemandSlots;

            /* Gaussian law for the getCPUDemand assignment */
            gLoad = Math.max((randGaussian.nextGaussian()*sigma)+mean, 0);
            int slot= (int) Math.round(Math.min(100,gLoad)*nbOfCPUDemandSlots/100);

            vmCPUDemand = slot*cpuConsumptionSlot*(int)tempVM.getCoreNumber();

            // Add a new event queue
            eventQueue.add(new LoadEvent(id++, currentTime,tempVM, vmCPUDemand));
            //  }
            currentTime+=exponentialDis(randExpDis, lambda);
            //        System.err.println(eventQueue.size());
        }
        Msg.info("Number of events:"+eventQueue.size());
        return eventQueue;
    }

    /* Compute the next exponential value for rand */
    private static double exponentialDis(Random rand, double lambda) {
        return -Math.log(1 - rand.nextDouble()) / lambda;
    }


    public static Deque<FaultEvent> generateSnoozeFaultQueue(XHost[] xhosts,  long duration) {
        LinkedList<FaultEvent> faultQueue = new LinkedList<FaultEvent>();
        long id=0;
        XHost tempHost;
        double currentTime = 0;
        double crashDuration = SimulatorProperties.getCrashDuration();
        long GLFaultPeriod = SnoozeProperties.getGLFaultPeriodicity();
        long GMFaultPeriod = SnoozeProperties.getGMFaultPeriodicity();

        if (GLFaultPeriod != 0) {

            // Kill GL in a specific way
            currentTime = GLFaultPeriod;
            do {

                tempHost = xhosts[SimulatorManager.getSGHostingHosts().size()];

                if (!ifStillOffUpdate(tempHost, faultQueue, currentTime)) {
                    // and change its state
                    // false = off , on = true
                    // Add a new event queue
                    faultQueue.add(new FaultEvent(id++, currentTime, tempHost, false));
                }
                if (currentTime + crashDuration < duration) {
                    //For the moment, downtime of a node is arbitrarily set to crashDuration
                    faultQueue.add(new FaultEvent(id++, currentTime + (crashDuration), tempHost, true));
                    //        System.err.println(eventQueue.size());
                }
                currentTime += GLFaultPeriod;
            } while (currentTime < duration);
        }

        // Random kill GM
        Random randHostPicker = new Random(SimulatorProperties.getSeed());
        currentTime = GMFaultPeriod;
        do {
            // Random select of one GM
            int index = -1;
            if (GLFaultPeriod == 0)
                index = randHostPicker.nextInt(SimulatorManager.getSGServiceHosts().size());
            else // GL faults have been already treated, so only consider GMs
             index = randHostPicker.nextInt(SimulatorManager.getSGServiceHosts().size()-1);

            // Please remind that node0 hosts VMs, so the first service node is Simulator.Manager.getSGHostingHosts().
            tempHost = xhosts[SimulatorManager.getSGHostingHosts().size()+index];

            if(!ifStillOffUpdate(tempHost, faultQueue, currentTime)) {
                // and change its state
                // false = off , on = true
                // Add a new event queue
                faultQueue.add(new FaultEvent(id++, currentTime, tempHost, false));
            }
            if (currentTime + crashDuration < duration) {
                //For the moment, downtime of a node is arbitrarily set to crashDuration
                faultQueue.add(new FaultEvent(id++, currentTime + (crashDuration), tempHost, true));
                //        System.err.println(eventQueue.size());
            }
            currentTime += GMFaultPeriod;
        }while(currentTime < duration);


        Msg.info("Number of events:"+faultQueue.size());
        for (InjectorEvent evt: faultQueue){
            Msg.info(evt.toString());
        }

        // Sort the list for the merge:
        Collections.sort(faultQueue, new Comparator<FaultEvent>() {
            @Override
            public int compare(FaultEvent o1, FaultEvent o2) {
                if (o1.getTime() > o2.getTime())
                    return 1 ;
                else if (o1.getTime() == o2.getTime())
                    return 0 ;
                else // o1.getTime() < o2.getTime()
                    return -1;
            }
        });

        return faultQueue;

    }

        public static Deque<FaultEvent> generateFaultQueue(XHost[] xhosts,  long duration, int faultPeriod){
        LinkedList<FaultEvent> faultQueue = new LinkedList<FaultEvent>();
        Random randExpDis=new Random(SimulatorProperties.getSeed());
        double currentTime = 0 ;
        double lambdaPerHost=1.0/faultPeriod ; // Nb crash per host (average)
        double crashDuration = SimulatorProperties.getCrashDuration();
        int nbOfHosts=xhosts.length;

        double lambda=lambdaPerHost*nbOfHosts;
        long id=0;
        XHost tempHost;

        currentTime+=exponentialDis(randExpDis, lambda);

        Random randHostPicker = new Random(SimulatorProperties.getSeed());

        while(currentTime < duration){
            // select a host
            int index = randHostPicker.nextInt(nbOfHosts);
            tempHost = xhosts[index];

            if(!ifStillOffUpdate(tempHost, faultQueue, currentTime)) {

                // and change its state
                // false = off , on = true
                // Add a new event queue
                faultQueue.add(new FaultEvent(id++, currentTime, tempHost, false));
            }
            if (currentTime + crashDuration < duration) {
                    //For the moment, downtime of a node is arbitrarily set to crashDuration
                    faultQueue.add(new FaultEvent(id++, currentTime + (crashDuration), tempHost, true));
                //        System.err.println(eventQueue.size());
            }
            currentTime += exponentialDis(randExpDis, lambda);
        }

        Msg.info("Number of events:"+faultQueue.size());
        for (InjectorEvent evt: faultQueue){
            Msg.info(evt.toString());
        }

        // Sort the list for the merge:
        Collections.sort(faultQueue, new Comparator<FaultEvent>() {
            @Override
            public int compare(FaultEvent o1, FaultEvent o2) {
                 if (o1.getTime() > o2.getTime())
                     return 1 ;
                 else if (o1.getTime() == o2.getTime())
                      return 0 ;
                 else // o1.getTime() < o2.getTime()
                    return -1;
            }
        });

        return faultQueue;
    }
    public static boolean isStillOff(XHost tmp, LinkedList<FaultEvent> queue, double currentTime, double crashDuration){
        ListIterator<FaultEvent> iterator = queue.listIterator(queue.size());
        while(iterator.hasPrevious()){
            FaultEvent evt = iterator.previous();
            if(evt.getState() == false){
                if (evt.getTime() + crashDuration  >= currentTime) {
                    if (evt.getHost()== tmp)
                        return true;
                }
                else
                    break;
            }
        }
        return false;
    }
    // if the node is off, we should remove the next On event and postpone it at currenttime +crashDuration
    // Note that the update is performed in the upper function.
    private static boolean ifStillOffUpdate(XHost tmp, LinkedList<FaultEvent> queue, double currentTime){
        ListIterator<FaultEvent> iterator = queue.listIterator(queue.size());
        while(iterator.hasPrevious()){
            FaultEvent evt = iterator.previous();
            if(evt.getState() == true){
                if (evt.getTime()  >= currentTime) {
                    if (evt.getHost()== tmp) {
                        iterator.remove();
                        return true;
                    }
                }
                else
                    break;
            }
        }
        return false;
    }

    public static Deque<InjectorEvent> mergeQueues(Deque<LoadEvent> loadQueue, Deque<FaultEvent> faultQueue) {
        LinkedList<InjectorEvent> queue = new LinkedList<InjectorEvent>();
        FaultEvent crashEvt;
        if (faultQueue != null)
            crashEvt = faultQueue.pollFirst();
        else
            crashEvt = null;
        LoadEvent loadEvt = loadQueue.pollFirst();
        // Here we are considering that the getCPUDemand event queue cannot be empty
        while(loadEvt != null){

            while (crashEvt != null && loadEvt.getTime()>crashEvt.getTime()){
                queue.addLast(crashEvt);
                crashEvt = faultQueue.pollFirst();
            }
            queue.addLast(loadEvt);
            loadEvt = loadQueue.pollFirst();
        }//while(loadEvt != null);

        while(crashEvt != null){
            queue.addLast(crashEvt);
            crashEvt = faultQueue.pollFirst();
        }

        writeEventQueue(queue);

        return queue;
    }

    private static void writeEventQueue(LinkedList<InjectorEvent> queue) {

        try {
            File file = new File("logs/events-queue.txt");
            file.getParentFile().mkdirs();
            BufferedWriter bw = new BufferedWriter(new FileWriter(file));
            for (InjectorEvent evt: queue){
                bw.write(evt.toString());
                bw.write("\n");
                bw.flush();
            }

            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Args : nbPMs nbVMs eventFile */
	public void main(String[] args) throws MsgException {

        /* Display the queue */

   /*     for(InjectorEvent evt: this.evtQueue){
            System.out.println(evt);
        }
*/
		/* Initialization is done in Main */
   
		if(!SimulatorManager.isViable()){
		   System.err.println("Initial Configuration should be viable !");
    	   System.exit(1);
       }


	   Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_MIG", 0);
	   Trace.hostVariableSet(SimulatorManager.getInjectorNodeName(), "NB_MC", 0);

      InjectorEvent evt = nextEvent();
      if(SimulatorProperties.goToStationaryStatus()){
          do {
              if ((evt instanceof LoadEvent) &&
                      (!SimulatorManager.willItBeViableWith(((LoadEvent)evt).getVm(),((LoadEvent) evt).getCPULoad()))) {
                  break;
              } else {
                  evt.play();
                  evt=nextEvent();
              }
          } while (true);
      }

      while(evt!=null){
		   if(evt.getTime() - Msg.getClock()>0)
         	   waitFor(evt.getTime() - Msg.getClock());
	       evt.play();
	       evt=nextEvent();
      }
      waitFor(SimulatorProperties.getDuration() - Msg.getClock());
	  Msg.info("End of Injection");
	  SimulatorManager.setEndOfInjection();

	  // Wait for termination of On going scheduling
	  waitFor(EntropyProperties.getEntropyPlanTimeout());
    }

	private InjectorEvent nextEvent() {
		return this.evtQueue.pollFirst();
	}
}