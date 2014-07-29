package scheduling.snooze;

import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.snooze.msg.*;

import java.util.ArrayList;
import java.util.Hashtable;

/**
 * Created by sudholt on 25/05/2014.
 */
public class GroupLeader extends Process {
    Host host; //@ Make private
    Hashtable<String, GMInfo> gmInfo = new Hashtable<String, GMInfo>(); //@ Make private
    private String inbox;
    private boolean thisGLToBeTerminated = false;

    static enum AssignmentAlg { BESTFIT, ROUNDROBIN };
    private int roundRobin = 0;

    public GroupLeader(Host host, String name) {
        super(host, name);
        this.host = host;
        this.inbox = AUX.glInbox(host.getName());
    }

    @Override
    public void main(String[] strings) {
        Test.gl = this;
//        Logger.debug("[GL.main] GL started: " + host.getName());
        procSendMyBeats();
        procGMInfo();
        while (true) {
            try {
                if (!thisGLToBeTerminated) {
                    SnoozeMsg m = (SnoozeMsg) Task.receive(inbox, AUX.ReceiveTimeout);
                    handle(m);
                    gmDead();
                } else {
                    Logger.err("[GL.main] TBTerminated: " + host.getName());
                    break;
                }
                sleep(AUX.DefaultComputeInterval);
            } catch (Exception e) {
                Logger.err("[GL.main] PROBLEM? Exception, " + host.getName() + ": " + e.getClass().getName());
                gmDead();
            }
        }
    }

    void handle(SnoozeMsg m) {
//        Logger.info("[GL.handle] GLIn: " + m);
        String cs = m.getClass().getSimpleName();
        switch (cs) {
            case "LCAssMsg" : handleLCAss(m);  break;
            case "NewGMMsg" : handleNewGM(m);  break;
            case "TermGMMsg": handleTermGM(m); break;
            case "TermGLMsg": handleTermGL(m); break;
            case "SnoozeMsg":
                Logger.err("[GL(SnoozeMsg)] Unknown message" + m);
                break;

            case "TestFailGLMsg":
                Logger.err("[GL.main] thisGLToBeTerminated: " + host.getName());
                thisGLToBeTerminated = true; break;
        }
    }

    void handleLCAss(SnoozeMsg m) {
        String gm = lcAssignment((String) m.getMessage());
        m = new LCAssMsg(gm, m.getReplyBox(), host.getName(), null);
        m.send();
        Logger.debug("[GL(LCAssMsg)] GM assigned: " + m);
    }

    void handleNewGM(SnoozeMsg m) {
        String gmHostname = (String) m.getMessage();
        if (gmInfo.containsKey(gmHostname)) Logger.err("[GL(NewGM)] GM " + gmHostname + " exists already");
        // Add GM
        GMInfo gi = new GMInfo(Msg.getClock(), new GMSum(0, 0, Msg.getClock()));
        gmInfo.put(gmHostname, gi);
        // Acknowledge integration
        Logger.info("[GL(NewGMMsg)] GM added: " + m);
    }

    void handleTermGL(SnoozeMsg m) {
        Logger.debug("[GL(TermGL)] GL to be terminated: " + host.getName());
        thisGLToBeTerminated = true;
    }

    void handleTermGM(SnoozeMsg m) {
        String gm = (String) m.getMessage();
        gmInfo.remove(gm);
        Logger.debug("[GL(TermGM)] GM removed: " + gm);
    }

    void gmBeats(SnoozeMsg m) {
        String gm = m.getOrigin();
        double ts = (double) m.getMessage();
        if (!gm.isEmpty()) {
            gmInfo.put(gm, new GMInfo(ts, gmInfo.get(gm).summary));
            Logger.info("[GL.gmBeats] TS updated: " + gm + ": " + ts);
        }
    }

    void gmCharge(SnoozeMsg m) {
        try {
            String gm = m.getOrigin();
            if (!gmInfo.containsKey(m.getOrigin())) return;
            GMInfo gi = gmInfo.get(gm);
            GMSumMsg.GMSum s = (GMSumMsg.GMSum) m.getMessage();
            GMSum sum = new GMSum(s.getProcCharge(), s.getMemUsed(), Msg.getClock());
            gmInfo.put(gm, new GMInfo(gi.timestamp, sum));
//            Logger.info("[GL(GMSum)] " + gm + ": " + sum + ", " + m);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void gmDead() {
        ArrayList<String> deadGMs = new ArrayList<String>();
        if (gmInfo.isEmpty()) return;
        for (String gm: gmInfo.keySet()) {
            GMInfo gi = gmInfo.get(gm);
            if (gi != null) {
                if (AUX.timeDiff(gi.timestamp) > AUX.HeartbeatTimeout) deadGMs.add(gm);
            }
        }
        for (String gm: deadGMs) {
            Logger.info("[GL.gmDead] GM dead, removed: " + gm + ": " + gmInfo.get(gm).timestamp);
            gmInfo.remove(gm);
        }
    }

    /**
     * Beats to multicast group
     */
    String lcAssignment(String lc) {
        if (gmInfo.size()==0) return "";
        String gm = "";
        switch (AUX.assignmentAlg) {
            case BESTFIT:
                double minCharge = 2, curCharge;
                GMSum cs;
                for (String s : gmInfo.keySet()) {
                    cs = gmInfo.get(s).summary;
                    curCharge = cs.procCharge;
                    if (minCharge > curCharge) {
                        minCharge = curCharge;
                        gm = s;
                    }
                }
//                Logger.info("[GL.lcAssignment] GM selected (BESTFIT): " + gm);
                break;
            case ROUNDROBIN:
                roundRobin = roundRobin % gmInfo.size(); // GMs may have died in the meantime
                ArrayList<String> gms = new ArrayList<>(gmInfo.keySet());
                gm = gms.get(roundRobin);
                roundRobin++;
                Logger.debug("[GL.lcAssignment] GM selected (ROUNDROBIN): " + gm + ", #GMs: " + gmInfo.size());
                break;
        }
        return gm;
    }

    void procGMInfo() {
        try {
            new Process(host, host.getName() + "-gmPeriodic") {
                public void main(String[] args) throws HostFailureException {
                    while (!thisGLToBeTerminated) {
                        try {
                            SnoozeMsg m = (SnoozeMsg)
                                    Task.receive(inbox + "-gmPeriodic", AUX.ReceiveTimeout);
//                            Logger.info("[GL.procGMInfo] " + m);

                            if      (m instanceof RBeatGMMsg) gmBeats(m);
                            else if (m instanceof GMSumMsg)   gmCharge(m);
                            else {
                                Logger.err("[GL.procGMInfo] Unknown message: " + m);
                                continue;
                            }

                            sleep(AUX.DefaultComputeInterval);
                        }
                        catch (TimeoutException e) {
                            Logger.exc("[GL.procGMInfo] PROBLEM? Timeout Exception");
                        }
                        catch (Exception e) {
                            Logger.exc("[GM.procGLInfo] Exception, " + host.getName() + ": " + e.getClass().getName());
//                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends beats to multicast group
     */
    void procSendMyBeats() {
        try {
            new Process(host, host.getName() + "-glBeats") {
                public void main(String[] args) {
                    String glHostname = host.getName();
                    while (!thisGLToBeTerminated) {
                        try {
                            BeatGLMsg m =
                                    new BeatGLMsg(Msg.getClock(), AUX.multicast+"-relayGLBeats", glHostname, null);
                            m.send();
                            Logger.info("[GL.procSendMyBeats] " + m);
                            sleep(AUX.HeartbeatInterval);
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    void dispatchVMRequest() {

    }

    void assignLCToGM() {

    }

    public class GMInfo {
        double timestamp;
        GMSum  summary;

        GMInfo(double ts, GMSum s) {
            this.timestamp = ts; summary = s;
        }
    }

    /**
     * GM charge summary info
     */
    public class GMSum {
        double procCharge;
        int    memUsed;
        double   timestamp;

        GMSum(double p, int m, double ts) {
            this.procCharge = p; this.memUsed = m; this.timestamp = ts;
        }
    }
}
