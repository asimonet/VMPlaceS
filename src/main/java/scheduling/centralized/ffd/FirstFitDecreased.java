package scheduling.centralized.ffd;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.AbstractScheduler;
import scheduling.centralized.CentralizedResolverProperties;
import simulation.SimulatorManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public abstract class FirstFitDecreased extends AbstractScheduler {
    private static int pass = -1;

    protected int nMigrations = 0;

    protected boolean useLoad;

    protected Collection<XHost> hostsToCheck;

    protected Queue<Migration> migrations;

    // Store the expected load of each host
    Map<XHost, Double> predictedCPUDemand = new HashMap<>();
    Map<XHost, Integer> predictedMemDemand = new HashMap<>();


    public FirstFitDecreased(Collection<XHost> hosts) {
        this(hosts, new Random(SimulatorProperties.getSeed()).nextInt());
    }

    public FirstFitDecreased(Collection<XHost> hosts, Integer id) {
        this.hostsToCheck = hosts;
        this.useLoad = SimulatorProperties.getUseLoad();
        this.migrations = new ArrayDeque<>();
        this.id=id;
        pass += 1;
    }

    @Override
    protected void applyReconfigurationPlan() {
        // Log the new configuration
        try {
            File file = new File("logs/ffd/reconfiguration/" + id + ".txt");
            file.getParentFile().mkdirs();
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));

            for(Migration m: migrations) {
                writer.write(m.toString());
                writer.write('\n');
            }

            writer.flush();
            writer.close();
        } catch (IOException e) {
            System.err.println("Could not write FFD log");
            e.printStackTrace();
            System.exit(5);
        }

        Migration m = null;
        while((m = migrations.poll()) != null) {
            if(m.dest.isOff())
                SimulatorManager.turnOn(m.dest);


            relocateVM(m.vm.getName(), m.src.getName(), m.dest.getName());
        }


        // Wait for all the migrations to terminate
        int watchDog = 0;

        while(this.ongoingMigrations()) {
            try {
                Process.getCurrentProcess().waitFor(1);
                watchDog ++;
                if (watchDog%2000==0){
                    Msg.info(String.format("You're waiting for %d migrations to complete (already %d seconds)", getMigratingVMs().size(), watchDog));
                    for(XVM vm: getMigratingVMs())
                        Msg.info("\t- " + vm.getName());
                    if(SimulatorManager.isEndOfInjection()){
                        Msg.info("Something wrong we are waiting too much, bye bye");
                        Process.getCurrentProcess().exit();
                    }
                }
            } catch (HostFailureException e) {
                e.printStackTrace();
            }
        }

        Msg.info("Reconfiguration done");
    }

    protected double computeUsage(XHost h, boolean prediction) {
        double cpuDemand = h.getCPUDemand();
        int memDemand = h.getMemDemand();

        if(prediction) {
            cpuDemand = predictedCPUDemand.get(h);
            memDemand = predictedMemDemand.get(h);
        }

        double cpu = 1 - ((h.getCPUCapacity()) - cpuDemand) / h.getCPUCapacity();
        double ram = 1 - ((h.getMemSize() - memDemand) / (float) h.getMemSize());
        return Math.max(cpu, ram);
    }

    protected double computeUsage(XHost host) {
        return computeUsage(host, false);
    }

    protected double computeUsage(Collection<XHost> hosts, boolean prediction) {
        int n = 0;
        float sum = 0F;

        for(XHost host: hosts) {
            if(host.isOff())
                continue;

            // If we are predicting a 0% usage, do no count it: the node will
            // be turned off
            double usage = computeUsage(host, prediction);
            if(!prediction || (prediction && usage > 0)) {
                n++;
                sum += usage;
            }
        }

        if(n > 0)
            return sum / n;
        else
            return -1D;
    }

    @Override
    public ComputingResult computeReconfigurationPlan() {
        ComputingResult result = new ComputingResult();
        long start = System.currentTimeMillis();

        ArrayList<XHost> overloaded = new ArrayList<>();

        // Find the overloaded hosts or force a reconfiguration
        // when at least 30% of the host are used less than 50%
        List<XHost> underLoaded = new ArrayList<>();

        for(XHost host : hostsToCheck) {
            double usage = computeUsage(host);
            double demand = host.computeCPUDemand();
            if(host.getCPUCapacity() < demand || host.getMemSize() < host.getMemDemand())
                overloaded.add(host);
            else {
                if (pass > 0 && host.isOn() && host.getCPUCapacity() > host.getCPUDemand()
                        && usage < 0.5) {
                    underLoaded.add(host);
                    //Msg.info(String.format("%s is underloaded: %.2f", host.getName(), usage));
                }
            }
        }

        manageOverloadedHost(overloaded, underLoaded, result);

        if(!migrations.isEmpty())
            result.state = ComputingResult.State.SUCCESS;
        else if(result.state != ComputingResult.State.RECONFIGURATION_FAILED)
            result.state = ComputingResult.State.NO_RECONFIGURATION_NEEDED;

        result.duration = ((double) System.currentTimeMillis() - start);
        return result;
    }

    protected abstract void manageOverloadedHost(List<XHost> overloadedHosts, List<XHost> underloadedHosts, ComputingResult result);


    class XHostComparator implements Comparator<XHost> {
        private int factor = 1;

        public XHostComparator() {
            this(false);
        }

        public XHostComparator(boolean decreasing) {
            if(decreasing)
                this.factor = -1;
        }

        @Override
        public int compare(XHost h1, XHost h2) {
            if(h1.getCPUCapacity() != h2.getCPUCapacity()) {
                return factor * (h1.getCPUCapacity() - h2.getCPUCapacity());
            }

            if(h1.getMemSize() != h2.getMemSize())
                return factor = (h1.getMemSize() - h2.getMemSize());

            if(h1.getNetBW() != h2.getNetBW())
                return factor = (h1.getNetBW() - h2.getNetBW());

            return 0;
        }
    }

    class XVMComparator implements Comparator<XVM> {
        private int factor = 1;
        private boolean useLoad = false;

        public XVMComparator(boolean useLoad) {
            this(false, useLoad);
        }

        public XVMComparator(boolean decreasing, boolean useLoad) {
            if(decreasing)
                this.factor = -1;

            this.useLoad = useLoad;
        }

        @Override
        public int compare(XVM h1, XVM h2) {
            if(useLoad && h1.getLoad() != h2.getLoad()) {
                return (int) Math.round(factor * h1.getLoad() - h2.getLoad());
            }

            if(h1.getCPUDemand() != h2.getCPUDemand()) {
                return factor * (int) Math.round((h1.getCPUDemand() - h2.getCPUDemand()));
            }

            if(h1.getMemSize() != h2.getMemSize())
                return factor * (h1.getMemSize() - h2.getMemSize());


            return h1.getName().compareTo(h2.getName());
        }
    }
}

class Migration {
    XVM vm;
    XHost src;
    XHost dest;

    public Migration(XVM vm, XHost source, XHost destination) {
        this.vm = vm;
        this.src = source;
        this.dest = destination;
    }

    public String toString() {
        return String.format("[Migration %s: %s -> %s]", vm.getName(), src.getName(), dest.getName());
    }
}
