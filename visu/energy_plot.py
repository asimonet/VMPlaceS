#! /usr/bin/env python

from __future__ import print_function

import os, sys, re, json, math
import traceback
import operator
import pprint
import datetime as dt
pp = pprint.PrettyPrinter(indent=4).pprint

import numpy as np

import matplotlib
import matplotlib.dates as md
import matplotlib.pyplot as plt
import matplotlib.patches as patches
import matplotlib.path as path
import matplotlib.animation as animation
import matplotlib.ticker as ticker
from itertools import cycle
import locale
locale.setlocale(locale.LC_ALL, 'en_US')
from pytz import timezone

def eprint(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)

# Determines the order of the bars in the plots
ORDER = ['Entropy', 'FFD']
#ORDER = ['Entropy', 'Lazy FFD', 'Optimistic FFD']
#ORDER = ['Lazy FFD', 'Optimistic FFD']

# Default values for Matplotlib
matplotlib.rcParams.update({
    'font.size': '10',
    'legend.fontsize': 9,
    'lines.linewidth': .5,
    'axes.linewidth': .5
})
LINEWIDTH=.5


# Check arguments
if len(sys.argv) != 2:
    eprint('Usage: ./energy_plot.py <experiment path>')
    sys.exit(1)

# Some functions
def to_bool(string):
    if string in ['true', 'True']:
        return True

    if string in ['false', 'False']:
        return False

    eprint("%s is not a boolean" % string)
    sys.exit(3)

def correct_name(name):
    names = {
        'LazyFirstFitDecreased': 'FFD',
        'OptimisticFirstFitDecreased': 'Optimistic FFD',
        'Entropy2RP': 'Entropy'}

    return names[name]

# time_on['Entropy']['node56'] = 17546.57
time_on = {}
last_on = None
def new_experiment(alg):
    global last_on
    time_on[alg] = {}
    last_on = {}

def end_experiment(time, alg):
    for node in last_on.keys():
        if last_on[node] is not None:
            node_off(node, time, alg)

def node_on(name, time, alg):
    if simulation_time is not None and time >= simulation_time:
        return

    if name in last_on and last_on[name] is not None:
        eprint("Node %s was already on since %.2f" % (name, time))
        sys.exit(1)

    last_on[name] = time

def node_off(name, time, alg):
    if simulation_time is not None and time >= simulation_time:
        return

    if last_on[name] is None:
        eprint("None %s was not on" % name)
        sys.exit(1)

    if name not in time_on[alg]:
        time_on[alg][name] = 0

    time_on[alg][name] += time - last_on[name]
    last_on[name] = None

########################################
# Get the number of turned off hosts
# and of migrations
########################################
n_turn_off = {}
n_migrations = {}
algos = []
n_on = {}
scheduler_ticks = {}

########################################
# Here is where stuff are
########################################
xp_dir = sys.argv[1]
run_log = os.path.join(xp_dir, 'run_all.log')
energy_log = os.path.join(xp_dir, 'energy.dat')
events = os.path.join(xp_dir, 'events')

# load and standard deviation must be the same
# for all the experiments in the log file
load = None
std = None
simulation_time = None
n_hosts = None

with open(run_log, 'r') as f:
    turn_off = None

    curr = None

    # Compile 3 patterns and read the logs
    start_pattern = re.compile(r'Running (\w+)(\s-D[\w\.]+\=([\w\.]+))? with (\d+) compute and (\d+) service nodes turning off hosts: (\w+), load.mean=(\d+), load.std=(\d+)')
    end_pattern = re.compile(r'\[.*\s(\d+\.\d+)\] \[.*\] End of Injection')
    off_pattern = re.compile(r'\[(.*\s)?(\d+\.\d+)\] \[.*\] Turn off (node\d+)')
    on_pattern = re.compile(r'\[(.* )?(\d+\.\d+)\] \[.*\] Turn on (node\d+)')
    migration_pattern = re.compile(r'End of migration of VM vm-\d+ from node\d+ to node\d+')

    scheduler_pattern = re.compile(r'\[(.*)\s(\d+\.\d+)\] \[.*\] Launching scheduler \(id = \d+\) - start to compute')

    for line in f:
        # This is a new experiment
        m = re.search(start_pattern, line)
        if m:
            turn_off = to_bool(m.group(6))
            n_hosts = int(m.group(4))

            if n_hosts not in n_turn_off:
                n_turn_off[n_hosts] = {True: {}, False: {}}

            if n_hosts not in n_migrations:
                n_migrations[n_hosts] = {True: {}, False: {}}

            if n_hosts not in scheduler_ticks:
                scheduler_ticks[n_hosts] = {True: {}, False: {}}

            if n_hosts not in n_on:
                n_on[n_hosts] = {}

            algo = correct_name(m.group(3).split('.')[-1])
            if algo not in algos:
                algos.append(algo)
                scheduler_ticks[n_hosts][turn_off][algo] = []

            if turn_off:
                n_on[n_hosts][algo] = []
                n_on[n_hosts][algo].append((0, 0))

            n_turn_off[n_hosts][turn_off][algo] = 0
            n_migrations[n_hosts][turn_off][algo] = 0

            curr = turn_off

            load = int(m.group(7))
            std = int(m.group(8))

            new_experiment(algo)

            print("Reading new experiment [algo=%s, computes=%d, turn_off=%s]" %
                    (algo, n_hosts, turn_off))

            continue

        # An experiment is over
        m = re.search(end_pattern, line)
        if m:
            time = float(m.group(1))
            end_experiment(time, algo)
            simulation_time = int(time)
            continue

        # The scheduler is running
        m = re.search(scheduler_pattern, line)
        if m:
            if algo not in scheduler_ticks[n_hosts][turn_off]:
                scheduler_ticks[n_hosts][turn_off][algo] = []
            scheduler_ticks[n_hosts][turn_off][algo].append(float(m.group(2)))
            continue

        # A node has been turned off
        m = re.search(off_pattern, line)
        if m:
            n_turn_off[n_hosts][curr][algo] += 1

            if turn_off:
                time = int(float(m.group(2)))
                n = n_on[n_hosts][algo][-1][1] - 1
                n_on[n_hosts][algo].append((time, n))

            node_off(m.group(3), float(m.group(2)), algo)
            continue

        # A node has been turned on
        m = re.search(on_pattern, line)
        if m:
            if turn_off:
                time = int(float(m.group(2)))
                n = n_on[n_hosts][algo][-1][1] + 1
                n_on[n_hosts][algo].append((time, n))

            node_on(m.group(3), float(m.group(2)), algo)
            continue

        # A VM has been migrated
        m = re.search(migration_pattern, line)
        if m:
            n_migrations[n_hosts][curr][algo] += 1

########################################
# Count the number of on VMs
########################################
n_vms = {}

dir_pattern = re.compile(r'(\w+)-([\w\d]+)-(\d+)-(true|false)')

# list dir in 'visu/events'
for item in os.listdir(events):
    m = re.search(dir_pattern, item)

    if m is None:
        continue

    # look for dirs like 'centralized-algo-64'
    if m.group(1) == 'centralized':
        algo = correct_name(m.group(2))
        turn_off = to_bool(m.group(4))
        n_hosts = int(m.group(3))

        if n_hosts not in n_vms:
            n_vms[n_hosts] = { True: {}, False: {} }

        event_file = os.path.join(events, item, 'events.json')
        print('Reading ' + event_file)

        with open(event_file, 'r') as f:
            n_vms[n_hosts][turn_off][algo] = {}

            # each line in this file is a JSON document
            for line in f:
                try:
                    event = json.loads(line)

                    if event['value'] == "NB_VM":
                        time = float(event['time'])
                        value = int(event['data']['value'])
                        n_vms[n_hosts][turn_off][algo][time] = value
                except:
                    t, value, tb = sys.exc_info()
                    print(str(t) + " " + str(value))
                    print(line)
                    traceback.print_tb(tb)
                    #sys.exit(1)

                if event['value'] != 'NB_VNS_ON':
                    continue

                n_vms[n_hosts][turn_off][algo][float(event['time'])] = int(event['data']['value'])

migration_ordered = []
########################################
# Get the energy metrics
########################################
energy = {}

with open(energy_log, 'r') as f:
    p = re.compile(r'(\d+) \w+ (\w+) (\w+) (\d+ )?([\d\.]+)')
    for line in f:
        m = re.match(p, line)
        n_hosts = int(m.group(1))
        implem = correct_name(m.group(2))
        turn_off = to_bool(m.group(3))
        if m.group(4) is not None:
            threshold = float(m.group(4))
        joules = float(m.group(5))

        if n_hosts not in energy:
            energy[n_hosts] = { True: {}, False: {} }

        energy[n_hosts][turn_off][implem] = joules / simulation_time / 1000

########################################
# Make the bar plot
########################################
ind = np.arange(len(algos)) # the x locations for the groups
width = 0.18

ordered_energy = {}
off_ordered = {}
migration_ordered = {}

for n_hosts in energy.keys():
    if n_hosts not in ordered_energy:
        ordered_energy[n_hosts] = { True: [], False: [] }

    for alg in ORDER:
        if alg not in energy[n_hosts][True]:
            continue

        ordered_energy[n_hosts][True].append(energy[n_hosts][True][alg])
        ordered_energy[n_hosts][False].append(energy[n_hosts][False][alg])

    print("ordered_energy %d:" % n_hosts)
    pp(ordered_energy[n_hosts])

    fig = plt.figure(figsize=(3.5, 3))
    ax1 = fig.add_axes([0.1, 0.1, 0.8, 0.8])
    pp(ax1)

    color1 = '#888888'
    color2 = '#FFFFFF'
    rects1 = ax1.bar(ind, ordered_energy[n_hosts][False], width, color=color1, linewidth=LINEWIDTH)
    rects2 = ax1.bar(ind + width, ordered_energy[n_hosts][True], width, color=color2, linewidth=LINEWIDTH)

    ax1.set_ylabel('Energy (Kilowatts)')
    ax1.set_xticks(ind + width)

    # Add some space on top
    lim = ax1.get_ylim()
    ax1.set_ylim(lim[0], lim[1] + .5)

    ax1.set_xticklabels(ORDER)

    ########################################
    # Make the line plots
    ########################################
    # Make sure the values here are in the same order as the energy values
    off_ordered[n_hosts] = []
    migration_ordered[n_hosts] = []
    for alg in ORDER:
        if alg not in n_turn_off[n_hosts][True]:
            continue

        off_ordered[n_hosts].append(n_turn_off[n_hosts][True][alg])
        migration_ordered[n_hosts].append(n_migrations[n_hosts][True][alg])

    print("off_ordered[%d]:" % n_hosts)
    pp(off_ordered[n_hosts])

    print("migration_ordered[%d]:" % n_hosts)
    print(migration_ordered[n_hosts])

    ax2 = ax1.twinx()
    ax2.set_ylabel('Number of migrations')
    migration_plot, = ax2.plot(ind + width, migration_ordered[n_hosts], 'k--^',
            linewidth=LINEWIDTH, ms=3)

    lim = ax2.get_ylim()
    ax2.set_ylim(lim[0], lim[1])
    ax2.set_yticks(np.arange(0, max(migration_ordered[n_hosts]), 500))

    for i,j in zip(ind + width, migration_ordered[n_hosts]):
        ax2.annotate(str(j), xy=(i+.025,j - 60), va='bottom', weight='bold',
                size='small')

    lgd = ax1.legend((rects1[0], rects2[0], migration_plot),
            ('Not turning off hosts', 'Turning off hosts', 'No. VM migrations'),
            loc='lower right')

    def find_filename(format):
        i = 0
        path = format % i
        while os.path.isfile(path):
            i += 1
            path = format % i

        return path

    save_path = find_filename('energy_%d_%d_%d_%%d.pdf' % (n_hosts, load, std))
    plt.savefig(save_path, transparent=True, bbox_extra_artists=(lgd,), bbox_inches='tight')
    print('Saved plot as ' + save_path)
    if os.system('which imgcat > /dev/null 2>&1') == 0:
        #os.system('imgcat ' + save_path)
        pass


########################################
# Make n_on plot
########################################
plots = {}

#styles = ['k-o', 'k-^', 'k-v', 'k-*']
linestyles = cycle([':', '-', '-.', '--'])

for n_hosts in n_on:
    fig = plt.figure(figsize=(5, 3))
    ax1 = fig.add_axes([0.1, 0.1, 0.8, 0.8])

    if n_hosts not in plots:
        plots[n_hosts] = {}

    i = 0
    for alg in ORDER:
        if alg not in n_on[n_hosts]:
            continue

        # Add the last point
        last_y = n_on[n_hosts][alg][-1][1]
        n_on[n_hosts][alg].append((simulation_time, last_y))

        print("n_on[%d]" % n_hosts)
        pp(n_on[n_hosts])

        tz = timezone('Europe/Paris')
        plots[n_hosts][alg], = ax1.step(map(lambda t:
            tz.localize(dt.datetime.fromtimestamp(t[0])), n_on[n_hosts][alg]),
                map(lambda t: t[1], n_on[n_hosts][alg]),
                linestyle=next(linestyles),
                linewidth=LINEWIDTH * 2)
        i += 1

    lgd = ax1.legend(plots[n_hosts].values(),
            n_on[n_hosts].keys(),
            loc='lower right')

    #ax1.set_ylim(0, n_hosts)

    plt.xticks(rotation=40)
    ax1.xaxis.set_major_locator(md.HourLocator())
    ax1.xaxis.set_minor_locator(md.MinuteLocator(interval=30))
    ax1.xaxis.set_major_formatter(md.DateFormatter('%H:%M'))

    ax1.yaxis.set_major_locator(ticker.MultipleLocator(10))
    ax1.yaxis.set_minor_locator(ticker.MultipleLocator(5))
    ax1.set_ylabel('Number of live hosts')

    save_path = find_filename('n_on_%d_%d_%d_%%d.pdf' % (n_hosts, load, std))
    plt.savefig(save_path, transparent=True, bbox_extra_artists=(lgd,), bbox_inches='tight')
    print('Saved plot as ' + save_path)
    if os.system('which imgcat > /dev/null 2>&1') == 0:
        #os.system('imgcat ' + save_path)
        pass

########################################
# Make vm_on plot
########################################
n_vms_ordered = {}

for n_hosts in n_vms:
    fig, ax1 = plt.subplots()

    if n_hosts not in n_vms_ordered:
        n_vms_ordered[n_hosts] = {}

    i = 0
    colors = ['g', 'b', 'm', 'y']
    for alg in ORDER:
        if alg not in n_vms[n_hosts][True]:
            continue

        n_vms_ordered[n_hosts][alg] = sorted(n_vms[n_hosts][True][alg].items())
        plots[n_hosts][alg], = ax1.plot(map(lambda t: t[0], n_vms_ordered[n_hosts][alg]),
                map(lambda t: t[1], n_vms_ordered[n_hosts][alg]), colors[i] + '.-', linewidth=LINEWIDTH, ms=8)

        #for tick in scheduler_ticks[n_hosts][True][alg]:
        #   ax1.plot((tick, tick), (450, 512), colors[i] + '-')

        i += 1

    ax1.set_xlim(0, simulation_time)

    lgd = ax1.legend(plots[n_hosts].values(),
            n_on[n_hosts].keys(),
            loc='lower right')

    save_path = find_filename('vms_on_%d_%d_%d_%%d.pdf' % (n_hosts, load, std))
    plt.savefig(save_path, transparent=True, bbox_extra_artists=(lgd,), bbox_inches='tight')
    print('Saved plot as ' + save_path)
    if os.system('which imgcat > /dev/null 2>&1') == 0:
        #os.system('imgcat ' + save_path)
        pass

