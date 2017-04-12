package scheduling.centralized.ffd;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.simgrid.msg.Msg;
import simulation.SimulatorManager;

import java.util.*;
import java.util.function.Predicate;

public class LazyFirstFitDecreased extends FirstFitDecreased {
    private float threshold = 1.0F;

    public LazyFirstFitDecreased(Collection<XHost> hosts) {
        this(hosts, new Random(SimulatorProperties.getSeed()).nextInt());
    }

    public LazyFirstFitDecreased(Collection<XHost> hosts, Integer id) {
        super(hosts, id);
        this.threshold = SimulatorProperties.getFfdThreshold() / 100;
    }

    @Override
    protected void manageOverloadedHost(List<XHost> overloadedHosts, List<XHost> underloadedHosts, ComputingResult result) {
        // The VMs are sorted by decreasing size of CPU and RAM capacity
        TreeSet<XVM> toSchedule = new TreeSet<>(new XVMComparator(true, useLoad));
        Map<XVM, XHost> sources = new HashMap<>();

        for(XHost host: SimulatorManager.getSGHostingHosts()) {
            predictedCPUDemand.put(host, host.getCPUDemand());
            predictedMemDemand.put(host, host.getMemDemand());
        }

        // Remove enough VMs so the overloaded hosts are no longer overloaded
        for(XHost host : overloadedHosts) {
            Iterator<XVM> vms = host.getRunnings().iterator();

            while((host.getCPUCapacity() * threshold < predictedCPUDemand.get(host) ||
                    host.getMemSize() < predictedMemDemand.get(host)) && vms.hasNext()) {
                XVM vm = vms.next();
                toSchedule.add(vm);
                sources.put(vm, host);
                predictedCPUDemand.put(host, predictedCPUDemand.get(host) - vm.getCPUDemand());
                predictedMemDemand.put(host, predictedMemDemand.get(host) - vm.getMemSize());
            }
        }


        for(XHost host: underloadedHosts) {
            for(XVM vm: host.getRunnings()) {
                toSchedule.add(vm);
                sources.put(vm, host);
            }
        }

        for(XVM vm: toSchedule) {

            // Try find a new host for the VMs, but give priority to the source host
            // (only if this scheduling is not forced, in which case we don't try to
            // minimize the number of migrations.
            XHost dest = null;
            Iterator<XHost> candidates = SimulatorManager.getSGHostingHosts().iterator();
            /*
            if(underloadedHosts.contains(sources.get(vm)))
                dest = candidates.next();
            else
                dest = sources.get(vm);
            */
            dest = candidates.next();

            // If the vm does not fit on the source node, pick another one
            while(dest.getCPUCapacity() < predictedCPUDemand.get(dest) + vm.getCPUDemand() ||
                    dest.getMemSize() < predictedMemDemand.get(dest) + vm.getMemSize()) {
                if(candidates.hasNext()) {
                    dest = candidates.next();
                }
                else {
                    result.state = ComputingResult.State.RECONFIGURATION_FAILED;
                    return;
                }
            }

            // Schedule the migration
            predictedCPUDemand.put(dest, predictedCPUDemand.get(dest) + vm.getCPUDemand());
            predictedMemDemand.put(dest, predictedMemDemand.get(dest) + vm.getMemSize());
            XHost source = sources.get(vm);
            if(!source.getName().equals(dest.getName())) {
                migrations.add(new Migration(vm, source, dest));
            }
        }

        // Check whether the usage score will be higher after the reconfiguration.
        // If not, drop it
        if(underloadedHosts.size() > 0) {
            double currentScore = computeUsage(underloadedHosts, false);
            double predictedScore = computeUsage(underloadedHosts, true);
            Msg.info(String.format("FirstFitDecreased cluster usage score (current/predicted): %.2f/%.2f",
                    currentScore, predictedScore));

            if (currentScore > 0 && predictedScore <= currentScore) {
                Msg.info("Dropping reconfiguration for under-used hosts");
                migrations.removeIf(new Predicate<Migration>() {
                    @Override
                    public boolean test(Migration migration) {
                        return underloadedHosts.contains(migration.src);
                    }
                });
            }
        }
    }
}
