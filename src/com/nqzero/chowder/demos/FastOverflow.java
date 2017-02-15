// copyright 2017 nqzero - see License.txt for terms

package com.nqzero.chowder.demos;

import kilim.Pausable;
import org.db4j.Command;
import org.db4j.Db4j;
import org.db4j.Db4j.Hunker;
import org.db4j.HunkArray;
import org.srlutils.Simple;
import org.srlutils.Util;

/*
 * run 'DemoEntropy init' first to build up tables
 * see DemoOverflow for a slower but more organic version of the same thing
 */

public class FastOverflow {
    String filename = "./db_files/b6.mmap";
    Hunker hunker;
    HunkArray.I map;
    void load() {
        hunker = Hunker.load(filename);
        hunker.qrunner.genc = Integer.MAX_VALUE - (1<<23);
        map = (HunkArray.I) hunker.arrays.get(0);
    }
    long sum(int [] vals) {
        long sum = Util.Ranged.sum(1,vals.length,vals);
        return sum;
    }
    /** number of players           */ int np = 1<<22; // also, range of quantities
    /** number of values per player */ int nv = 1<< 4;
    public void rotate(int nn) {
        for (int ii = 0; ii < nn; ii++) {
            dorotate();
            System.out.format( "rotate completed %5d\n", ii );
        }
    }
    public void dorotate() {
        asum = 0;
        for (int ii = 0; ii < np; ii++) {
            final int kplayer = ii;
            new Db4j.Task() {
                public void task() throws Pausable {
                    int [] data = new int[nv];
                    map.setdata(tid,kplayer*nv,data,new Command.RwInts(),nv);
//                    yield();
//                    asum += sum(data);
                }
            }.offer(hunker);
        }
        hunker.fence(null,10);
        System.out.format("FastOverflow: %12d\n", asum);
    }
    long asum = 0;
    public void close() {
        hunker.shutdown();
        hunker.close();
    }
    public static class Demo {
        public static void main(String [] args) {
            Simple.Scripts.cpufreqStash( 2300000 );
            FastOverflow test = new FastOverflow();
            test.load();
            test.rotate(1000);
            test.close();
            
        }
    }
    
}