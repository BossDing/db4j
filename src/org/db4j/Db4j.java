// copyright 2017 nqzero - see License.txt for terms

package org.db4j;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.nqzero.orator.Example;
import org.db4j.perf.DemoHunker;
import com.nqzero.directio.DioNative;
import java.util.HashMap;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import kilim.Pausable;
import org.srlutils.data.TreeDisk.Entry;
import org.srlutils.DynArray;
import org.srlutils.Files.DiskObject;
import org.srlutils.Simple;
import org.srlutils.Simple.Exceptions.IntpRte;
import org.srlutils.Timer;
import org.srlutils.Types;
import static org.srlutils.Simple.Exceptions.irte;
import static org.srlutils.Simple.Exceptions.rte;
import org.srlutils.Simple.Rounder;
import org.srlutils.Util;
import org.srlutils.data.Listee;
import org.srlutils.data.Quetastic;
import org.srlutils.data.TreeDisk;
import org.srlutils.hash.LongHash;
import static org.db4j.Db4j.Hunker.debug;
import org.srlutils.Callbacks.Cleanable;



// todo
// need to cache antipated writes somehow ... eg, the btree might get a page and make a modification
// using transaction tx1, and then later in tx1 that same page might be modified again eg by adding
// 2 values to an array in the same commit ... 
//   need to make sure that that 2nd modification is done using the
// result of the first, and not the on disk value, or the disk cache value

// use continuations for all io, eg btree stuff, to allow massive parallelism
// fix generational caching (so that writes don't need to be fenced)
//   remove the b2.btree.tests await kludge



public class Db4j {
    
    /** don't drop cache that's newer than the last iogen that's completed */
    public static final boolean useLastgen = false;
    public static final int bitsCache = 16;
    public static final int bitsIotree = 16;
    /** kludge to enable printing whenever a Task doesn't complete immediately 
     * ie requires reading from disk */
    public static boolean printNotDone = false;
    public static ByteOrder byteOrder = ByteOrder.nativeOrder();
    private static final boolean checkStatus = false;
    public static boolean dio = ! DioNative.skip;
    


    public static class Locals {
        public Hunker hunker;
        public int size;
        public long base;
        public Locals() { size = 0; }
        public Locals set(Hunker hunker,long base) {
            this.hunker = hunker;
            this.base = base;
            return this;
        }
        public int size() { return size; }
    }

    /** an atomic integer that also occupies a spot on disk */
    public static class LocalInt2 {
        public final int offset;
        Locals locals;
        public LocalInt2(Locals locals) {
            offset = locals.size;
            locals.size += size();
            this.locals = locals;
        }
        public int size() { return Types.Enum._int.size(); }
        public long offset() { return locals.base + offset; }
        /** return a command that will write val to disk */
        public Command.RwInt write(int val) {
            Command.RwInt cmd = new Command.RwInt().init( true);
            cmd.msg = "LocalInt::write";
            cmd.offset = offset();
            cmd.val = val;
            return cmd;
        }
        /** return a command that will read the atomic from the stored location on disk */
        public Command.RwInt read() {
            Command.RwInt cmd = new Command.RwInt().init( false);
            cmd.msg = "LocalInt::read";
            cmd.offset = offset();
            return cmd;
        }
    }

    

    public static interface Hunkable<TT extends Hunkable> {
        public String info();
        public int create();
        public void createCommit(long locBase);
        public String name();
        public TT set(Hunker hunker);
        public TT init(String $name);
        public void postInit(Transaction tid) throws Pausable;
        public void postLoad(Transaction tid) throws Pausable;
    }


    // this seems to work with 10-13 ... not sure why 9 bits is breaking
    /** the hunker block size */
    public static int blockSize = 12;

    /**
     * a class that manages a file and multiplexes io
     * needs to store the structure info for each component
     *
     */
    public static class Hunker implements Serializable {
        static final long serialVersionUID = 3365051556209870876L;
        transient public RandomAccessFile raf;
        /** bits per block        */  public           int  bb = Db4j.blockSize;
        /** block size            */  public transient int  bs;
        /** block offset bit mask */  public transient long bm;
        public long size;
        transient public Runner runner;
        transient public QueRunner qrunner;
        transient public Thread thread, qthread;
        transient volatile public Generation pending;
        transient public FileChannel chan;
        /** the unix file descriptor */  transient public int ufd;
        transient public ArrayList<Hunkable> arrays;
        transient public String name;
        transient public Loc loc;
        transient public boolean live;
        transient public Btrees.IA compRaw;
        transient public HunkLocals compLocals;
        transient public Btrees.IS kryoMap;
        transient public HunkLog logStore;

        transient public Example.MyKryo kryo;
        transient KryoFactory kryoFactory;
        transient KryoPool kryoPool;
        public Example.MyKryo kryo() { return ((Example.MyKryo) kryoPool.borrow()).pool(kryoPool); }

        public static final Debug debug = new Debug();
        public static final String PATH_KRYOMAP = "///db4j/hunker/kryoMap";
        public static final String PATH_LOGSTORE = "///db4j/hunker/logStore";

        public static int sleeptime = 10;
        transient FileLock flock;
        transient ClassLoader userClassLoader;

        /** for each field, null it out and call gc() -- use -verbose:gc to find deltas */
        public void checkLeaks() {
            System.out.println( "gc^10" );
            for (int ii = 0; ii < 10; ii++) System.gc();
            System.out.println( "thread" );
            thread = null;
            System.gc();
            System.out.println( "qthread" );
            qthread = null;
            System.gc();
            qrunner.checkLeaks();
            System.out.println( "qrunner" );
            qrunner = null;
            System.gc();
            System.out.println( "runner" );
            runner = null;
            System.gc();
        }

        transient public Util util;
        public class Util {
            /** return the number of blocks needed for size bytes */
            public int nblocks(int size) { return (size+bs-1) >> bb; }
            /** return the offset corresponding to kblock */
            long address(int kblock) { return ((long) kblock) << bb; }
        }
        
        public static class Debug {
            public final boolean test = true, intp = false, alloc = false;
            public final boolean cache = false, disk = false;
            /** print out reads that miss cache */
            public final boolean eeeread = false;
            /** QueRunner info, 0:none, 1:per-pass-info, 2:everything */
            public final int que = 0;
            /** Runner info, 0:none, 1:per-gen summary, 2:1+start, 3:2+dots */
            public final int tree = 0;
            public final boolean reason = false;
            /** record timing info for the disk loop */
            public final boolean dtime = false;
            public final boolean checkTasksList = false;
        }

        public static class Loc {
            public Locals locals = new Locals();
            /** number of allocated blocks - stored on disk */
            public final LocalInt2 nblocks = new LocalInt2( locals );
            /** number of allocated components */
            public LocalInt2 ncomp = new LocalInt2( locals );
        }

        /** load the Composite from the name'd file */
        public static Hunker load(String name) {
            DiskObject disk = org.srlutils.Files.load(name);
            Hunker ld = (Hunker) disk.object;
            ld.init( name, null );
            ld.load( (int) disk.size );
            return ld;
        }
        public void register(Hunkable ha) {
            arrays.add( ha );
        }
        /** read the range [k1, k2), values are live, ie must be fenced */
        public byte [] readRange(Transaction tid,long k1,long k2) {
            int len = (int) (k2 - k1);
            byte [] araw = new byte[ len ];
            iocmd( tid, k1, araw, false );
            return araw;
        }
        public void info() {
            for (Hunkable array : arrays)
                System.out.format( "Hunker.info:%20s:: %s\n", array.name(), array.info() );
        }
        public class LoadTask extends Task {
            volatile int count;
            int ncomp;
            boolean done;
            Command.RwInt nbc, ncc;
            int c1, c2;
            long start;
            public void load(long $start) {
                start = $start;
                c1 = compRaw.create();
                c2 = compLocals.create();
                long rawlen = Rounder.rup(start,align);
                long pcomp = rawlen + loc.locals.size();
                loc.locals.set( Hunker.this, rawlen );
                compRaw.createCommit(pcomp);
                compLocals.createCommit(pcomp+c1);
                long base = Rounder.rup(pcomp+c1+c2,align);
                offerTask(this);
                while (done==false) Simple.sleep(10);
                CompTask [] cts = new CompTask[ncomp];
                for (int ii = 0; ii < ncomp; ii++) {
                    arrays.add( null );
                    offerTask( cts[ii] = new CompTask(ii) );
                }
                for (int ii=0; ii < ncomp; ii++) {
                    CompTask task = cts[ii];
                    task.await(10);
                    // fixme - call reasons() on delay
                }
                kryoMap = (Btrees.IS) lookup(PATH_KRYOMAP);
                logStore = (HunkLog) lookup(PATH_LOGSTORE);
                
                System.out.format( "Hunker.load -- %d\n", ncomp );
            }
            public void task() throws Pausable {
                { 
                    nbc = put( tid, loc.nblocks.read() );
                    ncc = put( tid, loc.ncomp.read() );
                    yield();
                }
                {
                    ncomp = ncc.val;
                }
                { done = true; }
            }
            public class CompTask extends Task {
                public Listee.Lister<Db4j.Reason> reasons = new Listee.Lister();
                byte [] rdata, ldata;
                int ii;
                public CompTask(int $ii) { ii = $ii; }
                public void reason(Db4j.Reason reason) { reasons.append( reason ); }
                public void reason() {
                    for (Db4j.Reason reason : reasons)
                        System.out.println( reason );
                }
                public void task() throws Pausable {
                    {
                        byte [] b2 = compRaw.context().set(tid).set(ii,null).get(compRaw).val;
                        Hunkable ha = (Hunkable) org.srlutils.Files.load(b2);
                        Command.RwInt cmd = compLocals.get(tid,ii);
                        yield();
                        long kloc = cmd.val;
                        ha.set( Hunker.this ).createCommit(kloc);
                        ha.postLoad(tid);
                        arrays.set( ii, ha );
                        System.out.format( "Hunker.load.comp -- %d done, %s local:%d cmd:%d\n",
                                ii, ha.name(), kloc, cmd.val );
                        count++;
                    }
                }
            }
        }
        public void load(long start) {
            start();
            live = true;
            new LoadTask().load(start);
        }
        /** if doWrite then write, else read, data from tid at offset */
        public Command.RwBytes [] iocmd(Transaction tid,long offset,byte [] data,boolean doWrite) {
            return write( tid, offset, data, Types.Enum._byte.size(), data.length,
                    new Command.RwBytes().init(doWrite) );
        }
        /** if doWrite then write, else read, data from tid at offset */
        public Command.RwInts  [] iocmd(Transaction tid,long offset,int [] data,boolean doWrite) {
            return write( tid, offset, data, Types.Enum._int.size(), data.length,
                    new Command.RwInts().init(doWrite) );
        }
        /** if doWrite then write, else read, data from tid at offset */
        public Command.RwLongs [] iocmd(Transaction tid,long offset,long [] data,boolean doWrite) {
            return write( tid, offset, data, Types.Enum._long.size(), data.length,
                    new Command.RwLongs().init(doWrite) );
        }
        /**
         * use cmd as a template for an array-based action
         * apply it to data[0:length)
         * siz is the element size of data
         * add it to tid (if non-null) at offset
         * return the array of commands that have been created and added to tid
         */
        public <TT,SS extends Command.RwArray<TT,SS>> SS []
                write(Transaction tid,long offset,TT data,int siz,int length,SS cmd) {
            long end = offset + length * siz;
            long blockEnd = (offset&~bm)+bs;
            // fixme:dry -- should be able to extract siz from cmd
            // fixme:api -- should be able to use a non-0 base or get length from cmd

            // blocks [b1,b2)
            long b1 =     offset >> bb;
            long b2 = (end+bs-1) >> bb;
            int nhunks = (int) (b2 - b1);
            SS [] cmds = org.srlutils.Array.newArrayLike( cmd, nhunks );
            int kc = 0;

            for (long start = offset; start < end; start = blockEnd, blockEnd += bs) {
                long back = Math.min( blockEnd, end );
                int k1 = (int) (start - offset)/siz, k2 = (int) (back - offset)/siz;
                SS dup = cmd.dup();
                dup.set( data );
                dup.range( k1, k2-k1 );
                if (tid==null) put(     start, dup);
                else           put(tid, start, dup);
                cmds[ kc++ ] = dup;
            }
            return cmds;
        }
        public void print(String fmt,int nc,long [] vals) {
            int nv = vals.length;
            for (int kk = 0; kk < nv;) {
                int nt = Math.min( nv-kk, nc );
                for (int jj = 0; jj < nt; jj++, kk++)
                    System.out.format( fmt, vals[kk] );
                System.out.format( "\n" );
            }
        }
        /**
         * create the structure on disk
         * ---------------------------------
         * 0:
         *   serialized java object
         *   loc.nblock
         *   loc.ncomp
         * base (aligned by 4):
         *   offsets -- array of offsets to the component data
         *   rawOffsets -- array of offsets to the serialized components
         *   array of serialized components, back to back
         *   array of component data
         * 8*bs:
         *   the page data for the components starts here ...
         *
         */
        public void create() {
            start();
            kryoMap = new Btrees.IS();
            kryoMap.set(this).init(PATH_KRYOMAP);
            logStore = new HunkLog();
            logStore.set(this).init(PATH_LOGSTORE);
            
            CreateTask ct = new CreateTask();
            offerTask( ct );
            fence( ct, 10 );
            fence( null, 10 );
        }
        static final int align = 16;
        /*
         * file layout (create and load need to be kept in agreement):
         *   serialized hunker object, padded
         *   hunker locals
         *   compRaw locals
         *   compLocals locals, padded
         *   base (ie, base is aligned)
         * fixme -- need to verify that the locals aren't split across page boundries
         */
        public class CreateTask extends Task {
            public void task() throws Pausable {
                live = true;
                byte [] raw = org.srlutils.Files.save( Hunker.this );
                int rawlen = Rounder.rup(raw.length,align);
                int pcomp = rawlen+loc.locals.size();
                loc.locals.set( Hunker.this, rawlen );
                int ncomp = arrays.size();
                System.out.format( "Hunker.create -- %d\n", ncomp );
                long nblocks = runner.journalBase;
                long firstBlock = nblocks + runner.journalSize;
                for (int ii = 0; ii < nblocks; ii++)
                    put( tid, ii<<bb, new Command.Init() );
                put( tid, loc.nblocks.write((int) firstBlock) );
                put( tid, loc.ncomp.write(0) );
                iocmd( tid, 0, raw, true );
                // fixme::performance -- use compLocals for loc.locals
                //   would save a spot in cache and increase the likelihood of loc.nblock being hot
                //   bit of a chicken and the egg problem though
                int c1 = compRaw.create();
                int c2 = compLocals.create();
                compRaw.createCommit( pcomp );
                compLocals.createCommit( pcomp+c1 );
                compRaw.init( compRaw.context().set(tid) );
                long base = Rounder.rup(pcomp+c1+c2,align);
                Simple.softAssert( base < 1L*Hunker.this.bs*runner.journalBase );
                for (Hunkable ha : arrays)
                    create(tid,ha);
            }
        }
        public Hunkable lookup(Transaction tid,String name) throws Pausable {
            Command.RwInt ncomp = put(tid, loc.ncomp.read());
            tid.submitYield();
            int num = arrays.size();
            for (int ii=num; ii < ncomp.val; ii++)
                lookup(tid,ii);
            return lookup(name);
        }
        // fixme:untested
        public Hunkable lookup(Transaction tid,int index) throws Pausable {
            Hunkable ha;
            if (index < arrays.size()) {
                while ((ha = arrays.get(index))==null) kilim.Task.sleep(10);
                return ha;
            }
            Command.RwInt ncomp = put(tid, loc.ncomp.read());
            tid.submitYield();
            if (index >= ncomp.val) return null;
            boolean conflict = false;
            synchronized (arrays) {
                if (index < arrays.size())
                    conflict = true;
                else
                    arrays.set(index,null);
            }
            if (conflict) return lookup(tid,index);
            byte [] b2 = compRaw.context().set(tid).set(index,null).get(compRaw).val;
            ha = (Hunkable) org.srlutils.Files.load(b2);
            Command.RwInt cmd = compLocals.get(tid,index);
            tid.submitYield();
            long kloc = cmd.val;
            ha.set( Hunker.this ).createCommit(kloc);
            arrays.set(index,ha);
            return ha;
        }
        public void create(Transaction tid,Hunkable ha) throws Pausable {
            ha.set(this);
            byte [] araw = org.srlutils.Files.save(ha);
            Command.RwInt ncomp = put(tid, loc.ncomp.read());
            tid.submitYield();
            compRaw.context().set(tid).set(ncomp.val,araw).insert(compRaw);
            put(tid,loc.ncomp.write(ncomp.val+1));
            int len = ha.create();
            long offset = compLocals.alloc(ncomp.val,len,tid);
            ha.createCommit(offset);
            ha.postInit(tid);
            System.out.format( "hunker.create -- %5d len:%5d component:%s\n", ncomp.val, araw.length, ha );
        }
        /** attempt to close the backing file, returning any exception (instead of throwing) */
        public Exception close() {
            Exception ex = null;
            try { raf.close(); }
            catch (Exception e2) { ex = e2; }
            System.out.format( "Hunker.close: %s\n", ex==null ? "ok" : ex );
            return ex;
        }
        /** 
         * initialize all the transient fields
         * called for both initial creation and after loading from disk
         */
        public Hunker init(String name,Long fileSize) {
            try {
                bs = 1<<bb;
                bm = bs-1;
                this.name = name;
                raf = new RandomAccessFile( name, "rw" );
                ufd = DioNative.systemFD( raf.getFD() );
                loc = new Loc();
                util = new Util();
                long cs = raf.length();
                long nbytes;
                if (fileSize == null) nbytes = cs;
                else if (fileSize < 0) nbytes = Math.max( cs, -fileSize );
                else nbytes = fileSize;
                int nblocks = (int) (nbytes >> bb);
                if (nbytes > cs && dio)
                        DioNative.fallocate(ufd,cs,nbytes-cs);
                else if (nbytes > cs) {
                    raf.setLength( 0 );
                    raf.setLength( nbytes );
                    byte [] data = new byte[ bs ];
                    for (int ii = 0; ii < data.length; ii++) data[ii] = (byte) ii;
                    for (int ii = 0; ii < nblocks; ii++) raf.write( data );
                }
                this.size = nblocks;
                chan = raf.getChannel();
                chan.force( false );
                flock = chan.tryLock();
                if (flock==null) {
                    System.out.println( "Hunker.lock -- not acquired: " + name );
                    throw new RuntimeException( "could not acquire file lock on: " + name );
                }
                else
                    System.out.println( "Hunker.lock -- acquired: " + name );
                pending = null;
                runner = new Runner(this);
                qrunner = new QueRunner(this);
                arrays = new ArrayList();
                compRaw = new Btrees.IA();
                compLocals = new HunkLocals();
                compRaw.set(this);
                compLocals.set(this);
                kryo = new Example.MyKryo(new HunkResolver()).init();
                doRegistration(kryo);
                kryoFactory = new KryoFactory() {
                    public Kryo create() { return kryo.dup(); }
                };
                kryoPool = new KryoPool.Builder(kryoFactory).build();
            } catch (IOException ex) {
                throw new RuntimeException( ex );
            }
            return this;
        }
        

        void doRegistration(Kryo kryo) {
            kryo.register(RegPair.class);
        }
        
        
        public class HunkResolver extends Example.Resolver {
            public synchronized Registration registerImplicit(Class type) {
		Registration reg = getRegistration(type);
		if (reg != null)
                    return reg;
                int id = kryo.getNextRegistrationId();
                reg = new Registration(type, kryo.getDefaultSerializer(type), id);
                RegPair pair = new RegPair();
                pair.id = id;
                pair.name = type.getName();
                logStore.store(pair,(Example.MyKryo) kryo);
                System.out.format("RegPair.store: %4d %s\n",id,pair.name);
		return register(reg);
            }
            
        }

        public static class RegPair implements HunkLog.Loggable {
            public int id;
            public String name;
            public void restore(Hunker hunker) {
                try {
                    System.out.format("RegPair.restore: %4d %s\n",id,name);
                    Class type = Class.forName(name);
                    hunker.kryo().register(type,id);
                }
                catch (ClassNotFoundException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        public Transaction getTransaction() {
            Transaction tid = new Transaction().set(this);
            return tid;
        }

        /** set the command to the offset */
        public void put(long offset,Command cmd) {
            cmd.offset = offset;
        }

        /** add and return the command to the transaction at the offset */
        public <TT extends Command> TT put(Transaction tid,long offset,TT cmd) {
            cmd.offset = offset;
            return put(tid,cmd);
        }

        /** add and return the command to the transaction */
        public <TT extends Command> TT put(Transaction tid,TT cmd) {
            tid.add( cmd);
            return cmd;
        }

        /** throw a request to large runtime exception */
        public void throwRTL(int nreq,long avail) {
            throw Simple.Exceptions.rte(
                    null, "request too large -- req: %d, avail: %d", nreq, avail );
        }
        public int [] request(int [] reqs,Transaction tid) throws Pausable {
            Command.RwInt cmd = put( tid, loc.nblocks.read() );
            if (tid.submit()) kilim.Task.yield();
            int nblock = cmd.val;
            int [] blocks = new int[reqs.length];
            for (int ii = 0; ii < reqs.length; ii++) {
                blocks[ii] = nblock;
                nblock += reqs[ii];
            }
            put( tid, loc.nblocks.write( nblock ) );
            return blocks;
        }
        public int nblocks(Transaction tid) {
            Command.RwInt cmd = put( tid, loc.nblocks.read() );
            int nb = (tid.submit()) ? -1:cmd.val;
            return nb;
        }

        /** request a range of nreq hunks, guaranteed to be contiguous if true */
        public int [] request(int nreq,boolean contiguous,Transaction tid) throws Pausable {
            Command.RwInt cmd = put( tid, loc.nblocks.read() );
            if (tid.submit()) kilim.Task.yield();
            int nblock = cmd.val;
            int bits = 8;
            int nmod = nblock >> bits, n2 = (nblock+nreq) >> bits;
            if (debug.alloc && n2 > nmod)
                System.out.format( "hunker.request -- mod:%d\n", nblock+nreq );
            if (nreq > size - nblock) throwRTL( nreq, size - nblock );
            put( tid, loc.nblocks.write( nblock + nreq ) );
            int [] alloc = new int [ nreq ];
            for (int ii = 0; ii < nreq; ii++) alloc[ii] = nblock + ii;
            return alloc;
        }
        public Hunkable lookup(String name) {
            for (Hunkable ha : arrays)
                if (ha.name().equals( name )) return ha;
            return null;
        }

        public void start() {
            thread = new Thread( runner, "Disk Loop" );
            qthread = new Thread( qrunner, "Que Loop" );
            thread.start();
            qthread.start();
        }
            
        /** shut down the hunker - callable from outside the qrunner threads */
        public void shutdown() {
            offerTask( new Shutdown() );
            try {
                qthread.join();
                qthread = null;
                thread.join();
                thread = null;
            }
            catch (InterruptedException ex) { throw irte( ex ); }

            try { chan.force( false ); }
            catch (IOException ex) { System.out.println( "sync failed" ); }
            System.out.format(
                    "shutdown.stats -- disk:%5d, diskTime:%8.3f, diskWait:%8.3f, back:%5d\n",
                    runner.nwait, runner.diskTime, runner.waitTime, qrunner.nback );
        }
        /** force the cache to be committed to disk ... on return the commit is complete */
        public void forceCommit(int delay) {
            Db4j.ForceCommit commit = new Db4j.ForceCommit();
            offerTask( commit );
            while (!commit.done)
                Simple.sleep(delay);
        }
        /** sync the backing file to disk */
        public void sync() {
            forceCommit(10);
            try { chan.force( false ); }
            catch (IOException ex) {
                throw rte( ex, "attempt to sync Hunker file failed" );
            }
        }

        /** use fadvise to tell the OS that the entire backing file is "dontneed" in the cache */
        public void dontneed() {
            try { DioNative.fadvise( ufd, 0, size<<bb, DioNative.Enum.dontneed ); }
            catch(Exception ex) {}
        }
        public void fence(Db4j.Task task,int delay) {
            Db4j.Task oldest = null;
            while (task != null && task.id==0) Simple.sleep(delay);
            while (true) {
                OldestTask ot = new OldestTask();
                offerTask( ot );
                ot.await(delay);
                oldest = ot.oldest;
                if (task==null) task = ot;
                if (oldest==null || oldest.id >= task.id) break;
                Simple.sleep( delay );
            }
        }
        /** offer a new task */
        public <TT extends Queable> TT offerTask(TT task) {
            qrunner.quetastic.offer( qrunner.commandQ, task, Quetastic.Mode.Limit );
            return task;
        }

        

        public interface InvokeAble<TT> {
            TT execute(Db4j.Transaction tid) throws Pausable;
        }
        public interface ImploreAble {
            void execute(Db4j.Transaction tid) throws Pausable;
        }
        public class Invoke<TT> extends Tasky<Invoke<TT>> {
            InvokeAble<TT> body;
            public TT val;
            public Invoke(InvokeAble body) { this.body = body; }
            public void task() throws Pausable { val = body.execute(tid); }
        }
        public class Implore<XX extends ImploreAble> extends Tasky<Implore<XX>> {
            XX body;
            public Implore(XX body) { this.body = body; }
            public void task() throws Pausable { body.execute(tid); }
        }
        public <TT> TT offer(InvokeAble<TT> body) throws Pausable {
            Invoke<TT> invoke = new Invoke(body);
            offerTask(invoke);
            invoke.mbx.get();
            if (invoke.ex != null) throw invoke.ex;
            return invoke.val;
        }
        public void offerx(ImploreAble body) throws Pausable {
            Implore implore = new Implore(body);
            offerTask(implore);
            implore.mbx.get();
            if (implore.ex != null) throw implore.ex;
        }
        public <TT> Invoke<TT> future(InvokeAble<TT> body) {
            Invoke<TT> invoke = new Invoke(body);
            return offerTask(invoke);
        }
        public <TT extends ImploreAble> Implore<TT> futurex(TT body) {
            Implore<TT> implore = new Implore(body);
            return offerTask(implore);
        }
        public static abstract class Tasky<TT> extends Task {
            kilim.Mailbox<Integer> mbx = new kilim.Mailbox(1);
            public boolean postRun(boolean pre) {
                mbx.putnb(0);
                return false;
            }
            /** pausing wait for the task to complete, if the task thru an exception, rethrow it */
            public TT await() throws Pausable {
                mbx.get();
                if (ex != null) throw ex;
                return (TT) this;
            }
            /** blocking wait for the task to complete, if the task thru an exception, rethrow it */
            public TT awaitb() {
                mbx.getb();
                if (ex != null) throw ex;
                return (TT) this;
            }
            /** pausing wait for the task to complete, suppressing any task exception */
            public TT join() throws Pausable {
                mbx.get();
                return (TT) this;
            }
            /** blocking wait for the task to complete, suppressing any task exception */
            public TT joinb() {
                mbx.getb();
                return (TT) this;
            }
        }
        
        
        /** task has completed (or been cancelled???) - clean up the accounting info */
        public void cleanupTask(Db4j.Task task) {
            qrunner.tasks.remove(task);
            qrunner.ntask++;
            qrunner.state.completedTasks++;
        }
    }
    private static final long GENC_MAX_VALUE = Long.MAX_VALUE;
    private static final int GENC_MARGIN    = 1 <<  8;
    /**
     * read the command queue, and insert into the command iotree
     * reads are relative to the current generation
     * writes all become visible in the following generation
     */
    public static class QueRunner implements Runnable {
        public Hunker hunker;
        public boolean finished = false;
        /**
         *  the generation counter
         *  incremented for every transaction before it is registered
         *     and before committing the writes (entree)
         *  ie, it is the gen that was used for the last registered or entreed transaction
         *  fixme -- need to handle wrap-around
         */
        public long genc = gencUsageMarker(1);
        public Cache dc;
        public Generation map = null;
        public Quetastic    quetastic;
        public ConcurrentLinkedQueue<Queable> commandQ;
        public ConcurrentLinkedQueue<Predicable> blockQ;
        public Timer timer = new Timer();
        /** tree of rollback tasks ... they've been rolled back, need to be run from scratch */
        public TaskTree backlog = new TaskTree();
        /** list of all unfinished tasks */
        public Listee.Lister<Db4j.Task> tasks = new Listee.Lister();
        /** list of tasks that have returned from BlockNode, waiting to be run */
        public TaskTree waiting = new TaskTree();
        /** number of rollbacks in this generation */
        public int nback;
        public int ntask;
        public int taskID = 1;
        public HashMap<Integer,Latch> latchMap = new HashMap();
        boolean sendGen = true;
        public int kjournal = 0;
        public boolean clearout, clearack;
        public long lastgen = gencUsageMarker(0);
        public State state;

        /** 
         * marker method to indicate that a field is dependent on genc
         * this is important because genc could rollover so a mechanism is needed to handle that
         * this method allows searching for usages
         * @param val the desired value
         * @return the same value is returned
         */
        public static int gencUsageMarker(int val) { return val; }

        public QueRunner(Hunker $hunker) {
            hunker = $hunker;
            int qtb = 12;
            quetastic = new Quetastic().setCap( 1<<qtb, 1<<(qtb+1) );
            commandQ = new ConcurrentLinkedQueue();
            blockQ = new ConcurrentLinkedQueue();
            dc = new Cache().set(hunker);
            state = new State();
        }

        public void checkLeaks() {
            dc.checkLeaks();
            System.out.println( "Qrunner.cache" );
            dc = null;
            System.gc();
            System.out.println( "Qrunner.commandQ" );
            commandQ = null;
            System.gc();
            System.out.println( "Qrunner.blockQ" );
            blockQ = null;
            System.gc();
            if (map != null) map.checkLeaks();
            System.out.println( "Qrunner.map" );
            map = null;
            System.gc();
        }


        public void register(Transaction txn) {
            Simple.softAssert( !txn.inuse );
            if (dc.check) dc.check();
            // fixme::dry -- maybe should add a (synthetic) task instead ...
            txn.inuse = true;
            Simple.softAssert(genc < GENC_MAX_VALUE);
            txn.gen0 = ++genc;
            dc.txns.append( txn );
            if (dc.check) dc.check();
        }

        public long journalSpace() { return hunker.runner.journalSize - kjournal; }
        public Generation makeCT() {
            Generation ct = new Generation().genc( genc );
            ct.kjournal = kjournal;
            return ct;
        }
        public class State {
            int pl = (int) (.2*dc.maxsize), ph = (int) (.8*dc.maxsize);
            int addedTasks, completedTasks, max = 1024*32;
            void reset() {
                // the first time the cache exponentially-limits after an add, reduce the max
                // if the max is hit without hitting the exp-limit
                //     and the number of finishing tasks is max
                //     increase the max
                int ps = dc.precovered();
                if (ps < pl & addedTasks > 0) set(0.90);
                else if (ps >= pl & addedTasks >= max & completedTasks >= max) set(1.10);
                else if (false)
                    System.out.format( "Que.state.pass -- ps:%5d add:%5d fin:%5d max:%5d",
                            ps, addedTasks, completedTasks, max );
                addedTasks = completedTasks = 0;
            }
            void set(double mult) {
                int old = max;
                max *= mult;
                max = Math.max(max,1);
                if (false)
                System.out.format( "Que.state.set -- max:%8d old:%8d mult:%8.3f cache:%5d tasks:%5d\n",
                        max, old, mult, dc.precommit(), tasks.size() );
            }
            boolean limit() { return addedTasks>=max; }
        }
        public void run() {
            // nice to catch errors so can investigate, but makes it tougher to breakpoint on uncaught exceptions
            if (true) run2();
            else
            try { run2(); }
            catch (Exception ex) {
                ex.printStackTrace();
                Simple.spinDebug(false,"caused by: %s",ex);
            }
        }
        public void run2() {
            boolean predOnly = false, linefeed = false;
            double start = timer.tock();
            Thread current = Thread.currentThread();
            map = makeCT();
            try {
                int nres = 0, ntxn = 0;
                int maxtask = 1024*32;
                int maxwait=0;
                while ( ! current.isInterrupted() ) {
                    maxtask = state.max;
                    boolean active = ntxn > 10000
                            || map.iotree.size > 0
                            || (commandQ.isEmpty() && waiting.isEmpty())
                            || map.journalFull;
                    if (hunker.pending == null && active && sendGen) {
                        // fixme::optimize -- if you get a burst of cmds,
                        //   rather than sending the first alone
                        //   should que up at least what's available, or the first few of them ...
                        if ( ! map.iotree.isEmpty() ) {
                            if (linefeed) System.out.println();
                            linefeed = false;
                            sendGen = false;
                            if (debug.que > 0) {
                                double time = timer.tval();
                                double delta = time - start;
                                start = time;
                                System.out.format(
                                    "qr:snd io:%5d, %s, "
                                        + "tasks:%5d %5d %5d, ntxn:%5d %3d, "
                                        + "%5.1f max:%5d\n",
                                    map.iotree.size(), dc.info(),
                                    backlog.size(), tasks.size(), ntask, ntxn, nback,
                                    delta, state.max );
                            }
                            kjournal += map.journalSize();
                            if (map.commit & debug.que > 0)
                                System.out.format( "Cache:commit -- %8d %8d %8d\n",
                                        kjournal, journalSpace(), dc.ncrusoe );
                            if (map.commit) kjournal = 0;
                            Simple.softAssert( map.kjournal < hunker.runner.journalSize );
                            Simple.softAssert( kjournal < hunker.runner.journalSize );
                            ntxn = 0;
                            map.gen2 = genc+1;
                            int numio = map.iotree.size();
                            hunker.pending = map;
                            if (debug.checkTasksList) tasks.check();
                            dc.complete( genc, 0 ); // fixme -- should this depend on a new map ???
                            state.reset();
                            map = makeCT();
                            dc.scrub();
                            if (dc.d2 > 2*numio) dc.submitCache();
                        }
                        if (finished) break;
                    }

                    Queable cmd = null;
                    try {
                        cmd = null;
                        // fixme - structural modifications should now be using blockQ not cmdq
                        Predicable pred = blockQ.poll();
                        if (pred != null) {
                            pred.handle( hunker );
                            continue;
                        }
                        if (map.journalFull) {
                            // fixme:opt -- could still handle reads
                            System.out.print( "." );
                            linefeed = true;
                            Simple.sleep(10);
                            continue;
                        }
                        maxwait = maxtask;
                        int ps = dc.precovered();
                        int p2 = dc.precommit();
                        int pl = state.pl;
                        if (ps < pl) {
                            int pow2 = 32-Integer.numberOfLeadingZeros(maxtask-1);
                            int delta = pl / pow2;
                            int numlevels = (pl-ps) / delta + 1;
                            maxwait = ps >> numlevels;
                            maxwait = Math.max(maxwait,4);
                        }
                        boolean relegate = ps < pl & map.iotree.size() > maxwait;
                        if (relegate | p2 < 100) {
                            System.out.print( relegate ? ">" : "," );
                            map.nonEmptyify();
                            linefeed = true;
                            Simple.sleep(10);
                            continue;
                        }
                        if (! waiting.isEmpty()) {
                            Db4j.Task task = waiting.pop();
                            if (task != null) task.preRun(hunker);
                            ntxn++;
                            continue;
                        }
                        if (clearack) {
                            clearout = clearack = false;
                            dc.dropNotReady();
                            dc.check();
                            if (dc.outstanding != 0)
                                System.out.format( "hunker.clearack -- outstanding:%5d\n", dc.outstanding );
//                            Simple.softAssert( dc.outstanding==0 );
                            continue;
                        }
                        else if (clearout | ps < pl | state.limit()) {
                            map.nonEmptyify();
                            Simple.sleep(10);
                            linefeed = true;
                            System.out.print( "-" );
                            continue;
                        }
//                        if (dc.prestand() < ph | dc.predefoe() < ph & (map.commit | dc.defoe != dc.crusoe) ) {
//                            System.out.print( "," );
//                            linefeed = true;
//                            Simple.sleep(10);
//                            continue;
//                        }
                        if (predOnly) { Simple.sleep(1); continue; }
                        if (cmd == null && ! backlog.isEmpty()) {
                            Db4j.Task task = backlog.pop();
                            task.init(hunker);
                            task.runTask(hunker);
                            ntxn++;
                            continue;
                        }
                        if (false & tasks.size > maxtask) {
                            System.out.print( "+" );
                            linefeed = true;
                            Simple.sleep(10);
                            continue;
                        }
                        if (cmd == null) cmd = commandQ.poll();
                        if (cmd == null) {
                            if (debug.que > 1)
                                System.out.format( "QueRunner:take -- returned null\n" );
                            Simple.sleep(Hunker.sleeptime);
                        }
                        else {
                            ntxn++;
                            nres++;
                            cmd.entree( this );
                        }
                    }
                    catch (DropOutstandingException ex) {
                        System.out.format( 
                                "DropOutstanding ----------------------------------------\n" );
                        clearout = true;
                        map.dropOutstanding = true;
                        map.nonEmptyify();
                        Simple.softAssert( ! clearack );
                    }
                    if (nres >= 64 || (cmd==null && nres > 0)) {
                        quetastic.resolve( nres );
                        nres = 0;
                    }
                    if (nres==0) { Simple.sleep(0); }
                    if (nres==0 & false)
                        dc.check();
                }
            }
            catch (IntpRte ex) {
                int count = quetastic.count.get();
                if (debug.intp)
                    System.out.format( "QueRunner:interrupted -- tree:%d, que:%d\n", 
                            map.iotree.size(), count);
                current.interrupt();
            }
        }

        public void handleRef(Command.Reference cmd) {
            BlockNode block = getBlock(cmd.offset>>hunker.bb,null,map);
            Simple.softAssert(block.cache==null || block.cache.ready());
            if (block.writeCache==null)
                block.writeCache = dc.putCache(cmd.offset,genc,cmd.txn.gen0,true);
            cmd.run((int)(cmd.offset&hunker.bm),Command.Page.wrap(null),hunker,false);
        }
        
        /** handle a Command.Init - initialize the cache for the specified page */
        public boolean handleInit(Command.Init cmd) {
            long gen2 = cmd.txn.gen0;
            CachedBlock c2 = dc.getCache(cmd.offset,genc-1,gen2);
            Simple.softAssert(c2==null);
            CachedBlock cb = dc.putCache(cmd.offset,     0,gen2,false);
            byte [] data = cmd.data==null ? new byte[hunker.bs] : cmd.data;
            cb.setData( data, true );
            return true;
        }
        /** process the write */
        public void handleWrite(Command cmd) {

            /*
             * add the block to the iotree if needed
             * add the write to the block
             * get the read cache if not already ref'd in block
             * create and insert the write cache if not already ref'd in the block
             * add the write to the read cache
             * move the read and write caches to the end of the byUpdate list
             * move the write cache to the end of the covered list
             * apply the write to the write cache if ready
             */
            BlockNode block;
            CachedBlock wc, bc;
            
            // txn is null only for immediate writes, eg HunkLog.set
            long gen2 = cmd.txn==null ? genc:cmd.txn.gen0;
            {
                block = getBlock(cmd.offset >> hunker.bb, null, map);
                wc = block.writeCache;
                bc = block.cache;
            }
            if (wc==null) {
                if (bc!=null)
                    Simple.softAssert(!bc.ready() | bc.submit);
                if (bc==null) bc = dc.getCache(cmd.offset,genc-1,gen2);
                if (bc==null) bc = dc.putCache(cmd.offset,     0,gen2,false );
                if (dc.check) dc.check();
                wc = block.writeCache = dc.putCache(cmd.offset,genc,gen2,true);
                dc.cover(bc,map.commit);
                if (debug.que > 0)
                    Simple.softAssert(
                        wc.older == bc,
                        "the previous entry should be the block cache ... it's not" );
                if (bc.ready()) {
                    wc.setData( Util.dup(bc.data), true );
                    block.cache = null;
                }
                else block.cache = bc;
                if (dc.check) dc.check();
            }
            else {
                Simple.softAssert(bc==null || !bc.ready());
                wc.gen = genc;
                dc.update(wc,gen2);
                dc.covered.moveToTail(wc);
            }
            block.commit = map.commit;
            map.addWrite(hunker,block,cmd);
            Simple.softAssert( kjournal + map.journalSize() <= hunker.runner.journalSize );
            if (wc.ready()) {
                Command.Page b2 = Command.Page.wrap(wc.data);
                cmd.run( (int) (cmd.offset&hunker.bm), b2, hunker,false );
                cmd.clean();
                wc.data = b2.data;
            }
        }
        /** if the read can be handled by cache, 
         *    run+book it and return null, else return the cache info */
        public PreRead preRead(Command cmd,Command first) {
            boolean alloc = false;
            long gen2 = cmd.txn.gen0;
            CachedBlock c2 = dc.getCache(cmd.offset,cmd.gen,gen2);
            if (c2==null) alloc = true;
            Command.Page buf = runCache( c2, cmd, first );
            if (buf==null) {
                if (c2==null) {
                    c2 = dc.putCache(cmd.offset,0,gen2,false);
                    if (Hunker.debug.eeeread)
                        System.out.format("preRead.put -- %5d\n",cmd.offset/hunker.bs);
                }
                return new PreRead(cmd,alloc,c2);
            }
            cmd.run( (int) (cmd.offset & hunker.bm), buf, hunker,false );
            cmd.book( hunker );
            return null;
        }
        /** commit a previously calculated preRead 
         *    - there must not be any modification of the cache */
        public void commitRead(PreRead pr) {
            CachedBlock cache = pr.entry;
            BlockNode block = getBlock(cache.kblock,cache, map);
            block.addRead(pr.cmd);
        }
        /** cancel a read that hasn't been committed, ie delete the cache entry if it was alloc'd */
        public void cancelRead(PreRead pr) {
            // fixme -- remove checks ...
            boolean dbg = false;
            if (dbg) dc.check();
            if (pr.alloc)
                dc.cancelPreread( pr.entry );
            if (dbg) dc.check();
        }
        /** insert the block entry if needed */
        public BlockNode getBlock(long kblock,CachedBlock cache,Generation iomap) {
            BlockNode block = iomap.iotree.get(kblock);
            if (block==null) {
                block = new BlockNode().kblock(kblock);
                block.init();
                iomap.put( block );
                block.cache = cache;
            }
            return block;
        }
        /** check whether cmd has been overwritten between the cmd creation and gen */
        public boolean overwritten(Command cmd,Command first) {
            // invariants: we've read cmd, so there must be a cached block of it
            //   compare the value at the time of the read
            //   with the data in the most recently cached block
            long kblock = cmd.offset >> hunker.bb;
            CachedBlock cb = dc.tree.get(kblock,genc);
            if (cb==null) {
                // fixme -- known crash, but hard to reproduce
                //   assume that it's caused by a read of a block that's been init'd
                //   and as such, shouldn't be in the cache
                //   but would like to confirm this before fixing it
                System.out.format( "overwritten::cacheNotFount -- " );
                Command.print(hunker,false,cmd);
                Command.print(hunker,true,first);
            }
            if (cb==null) {
                // the block must have been init'd ... verify that by runCache()
                Command.Page buf = runCache(cb,cmd,first);
                Simple.softAssert(buf != null);
                return false;
            }
            if (cb.gen <= cmd.gen)
                return false;
            if (cmd.unchangeable()) return true;
            Command.Page buf = runCache(cb,cmd,first);
            int offset = (int) (cmd.offset & hunker.bm);
            return cmd.changed( buf, offset );
        }
        /** 
         *  run the command with data, after applying all writes between first and cmd, onto tmpbuf 
         *  if data is null and cmds aren't full, return null
         *  else a page that cmds can be run against
         */
        public Command.Page runCache(CachedBlock cb,Command cmd,Command first) {
            byte [] data = null;
            if (cb != null && cb.ready()) data = cb.data;
            final int bb = hunker.bb;
            final long bm = hunker.bm;
            long kblock = cmd.offset >> bb;
            Command.Page buf = null;
            for (Command c2 = first; c2 != cmd; c2 = c2.next)
                if (c2.write()) {
                    // fixme - could skip unless the write overlaps
                    if ((c2.offset >> bb)==kblock) {
                        if (buf==null)
                            // fixme:opt -- if c2.full() then this might not be needed
                            buf = Command.Page.wrap(data).dupify();
                        // fixme - should be able to skip the dupify if deferrable
                        c2.run( (int) (c2.offset&bm), buf, hunker,false );
                    }
                }
            if (buf==null & data != null)
                buf = Command.Page.wrap(data).order(byteOrder);
            return (buf==null || buf.data==null) ? null : buf;
        }
    }


    /**
     *  thread that handles disk io
     */
    public static class Runner implements Runnable {
        public Hunker hunker;
        public Timer timer = new Timer();
        public boolean finished = false;
        public ByteBuffer buf;
        public int totalReads = 0;
        public int totalWrites = 0;
        public int nwait = 0;
        public double diskTime = 0, waitTime = 0;
        public Runner(Hunker $hunker) {
            hunker = $hunker;
            buf = ByteBuffer.allocate( hunker.bs );
            buf.order(byteOrder);
        }


        public void run() {
            Thread current = Thread.currentThread();
            timer.tic();
            double delta = 0, t0 = timer.tval();
            try {
                while ( ! current.isInterrupted() && !finished ) {
                    // fixme::spinlock -- use a que ???
                    if (hunker.pending == null) {
                        org.srlutils.Simple.sleep(Hunker.sleeptime);
                        nwait++;
                    }
                    else {
                        double t1=0, t2=0;
                        if (debug.dtime) t1 = timer.tval();
                        waitTime += t1-t0;
                        Generation map = hunker.pending;
                        int mts = map.iotree.size();
                        if (debug.tree >= 2) {
                            System.out.format( "Runner:start -- size: %8d", mts );
                        }
                        process( map );
                        if (debug.dtime) t2 = timer.tval();
                        diskTime += t2-t1;
                        if (debug.tree >= 1) {
                            if (debug.tree >= 2) System.out.println();
                            System.out.format(
                                "Runner:empty -- size: %8d --> %8.3f, wait:%8.3f msec\n",
                                mts, t2-t1, t1-t0 );
                        }
//                            Simple.sleep(100);
                        hunker.pending = null;
                        hunker.qrunner.blockQ.offer( map );
                        t0 = t2;
                    }
                }
            }
            catch (IntpRte ex) {
                if (debug.intp) System.out.format( "Runner:interrupted\n" );
                current.interrupt();
            }
        }
        long journalBase = 128; // 15*(1<<18);
        long journalSize = 1<<18;
        int kk = 0;

        /** write the buffer to the journal and increment the journal-block pointer */
        public void journal(byte [] data) throws IOException {
            if (data==null) Simple.softAssert(false);
            write( journalBase + kk++, ByteBuffer.wrap(data) );
        }
        /** write the buffer to the journal at journal-block kj */
        public void journal(int kj) throws IOException { write(journalBase + kj,buf); }
        /** write the buffer to block kblock */
        public void write(long kblock,ByteBuffer b2) throws IOException {
            long offset = kblock << hunker.bb;
            b2.clear();
            int nwrite = hunker.chan.write( b2, offset );
            if (nwrite < hunker.bs)
                throw new RuntimeException( "partial write: " + nwrite );
            if (dio) DioNative.fadvise( hunker.ufd, offset, hunker.bs, DioNative.Enum.dontneed );
        }
        /**
         * the header is all longs ... a magic number, the number of blocks, the kblocks for each block
         *   always a whole number of pages (ie, there may be empty space after the kblocks section)
         * then the blocks are written, ie the block.data
         * then 1 page as a footer, initially zeros, switched to ones when the block data is complete
         */
        public int header(Generation gen) throws IOException {
            if (gen.nwrite==0) return kk;
            java.util.Arrays.fill( buf.array(), (byte) 0 );
            int footer = kk + gen.nheader + gen.nwrite;
            journal(kk);       // zero the header
            journal(footer+0); // zero the footer
            if (footer+1 < journalSize)
                journal(footer+1); // zero the header for next generation

            buf.clear();
            long magic = 115249; // could use any non-zero, but the largest cuban prime is cool
            buf.putLong(magic);
            buf.putLong(gen.nwrite);
            if (Hunker.debug.disk)
                Simple.softAssert( buf.position() == gen.ntitle*Types.Enum._long.size);
            for (BlockNode block : gen.blocks)
                if (block.writ) {
                    if (! buf.hasRemaining()) {
                        journal(kk++); // sub-ultimate header pages
                        buf.clear();
                    }
                    buf.putLong( block.kblock );
                }
            journal(kk++); // ultimate header page
            return footer;
        }
        public void footer(Generation generation,int footer) throws IOException {
            Simple.softAssert( footer==kk );
            if (generation.nwrite==0) return;
            java.util.Arrays.fill( buf.array(), (byte) 0xff );
            journal(kk++); // footer
            if (Hunker.debug.disk)
                Simple.softAssert(Generation.nfooter==1);
        }
        public void process(Generation generation) {
            try {
                int footer = 0;
                int bs = hunker.bs;
                int bb = hunker.bb;
                int ufd = hunker.ufd;
                FileChannel chan = hunker.chan;
                QueRunner qrunner = hunker.qrunner;
                
                generation.sort();
                
                Simple.softAssert( kk==generation.kjournal || generation.kjournal==0 );
                kk = generation.kjournal;
                Simple.softAssert( kk < journalSize );
                footer = header(generation);
                if (dio) for (BlockNode block : generation.blocks) {
                    if (!block.dontRead())
                        DioNative.fadvise( ufd, block.kblock << bb, bs, DioNative.Enum.willneed );
                }
                for (int ii = 0; ii < generation.blocks.length; ii++) {
                    BlockNode block = generation.blocks[ii];
                    if (debug.tree >= 3) System.out.format( "." );
                    long offset = block.kblock << bb;

                    byte [] data = null;

                    if (! block.dontRead() ) {
                        ByteBuffer b2 = ByteBuffer.wrap( data = new byte[bs] );
                        if (offset < 0 | offset > hunker.size)
                            rte(null,"Disk.read -- out of range block:%d task:%s",
                                    block.kblock, block.cmds.get(0).txn.task );
                        final int nread = chan.read( b2, offset );
                        if (nread < bs) {
                            // handle anomalies in the qrunner
                            block.diskAnomaly = new DiskAnomaly() {{ numRead = nread; }};
                            qrunner.blockQ.offer( block );
                            continue;
                        }
                        if (dio) DioNative.fadvise( ufd, offset, bs, DioNative.Enum.dontneed );
                        totalReads++;
                    }
                    boolean done = block.runBlock(hunker,data);

                    // future-proof: for small caches, where the pending writes are a significant
                    //   size of the cache, could use double-blind (writes overlayed on 0s, 1s)
                    //   don't need the reads all at once
                    if (done) {
                        qrunner.blockQ.offer( block );
                        generation.blocks[ii] = null;
                    }
                }
                footer(generation,footer);
                chan.force(false);
                if (finished && generation.nwrite > 0)
                    System.out.format( "XXXXXXX.finalWrite %d\n", generation.nwrite );
                for (BlockNode block : generation.blocks) {
                    if (block != null) {
                        block.postRun(hunker);
                        qrunner.blockQ.offer( block );
                    }
                }

            } catch (IOException ex) {
                throw new RuntimeException( ex );
            }
        }
    }

    public static class PreRead {
        Command cmd;
        PreRead next;
        boolean alloc;
        CachedBlock entry;
        public PreRead(Command $cmd,boolean $alloc,CachedBlock $entry)
            { cmd=$cmd; alloc=$alloc; entry=$entry; }
    }
    
    /** a set of io to be completed as a single generation */
    public static class Generation implements Predicable {
        public int nfound;
        /** immutable, the qrunner txn counter at the moment this generation begins */
        public long genc = QueRunner.gencUsageMarker(0);
        /** immutable, the next qrunner txn counter upon map rotation */
        public long gen2 = QueRunner.gencUsageMarker(0);
        public LongHash.Map<BlockNode> iotree = new LongHash.Map().init(bitsIotree+1);

        /** the number of blocks that need to be written to disk */
        public int nwrite = 0;
        public int ntitle = 2;
        /** the number of blocks that are needed for the journal header */
        public int nheader = 1;
        public int kjournal;
        public boolean journalFull, commit;
        public boolean dropOutstanding;
        private Task completedTasksList;
        boolean shutdown;
        public Generation genc(long val) { this.genc = val; return this; }
        
        public static int bitsHeader = 3, nfooter = 1;

        /** free any resources so that old generations can be kept in memory efficiently */
        public void cleanup() { iotree = null; }
        /** 
         * the journal consists of a header, the block data, and a footer
         * header: number of write blocks, kblock for each block
         * footer: all zeros for not complete, all ones for complete
         */
        public int journalSize() { return nwrite==0 ? 0:nwrite + nheader + nfooter; }
        /** the upper bound on the size of the journal after adding nw writes */
        public int journalBound(Hunker hunker,int nw) {
            // worst case is every write adds a block
            int nlongs = nwrite + ntitle + nw;
            int nh = Simple.Rounder.divup2( nlongs << bitsHeader, hunker.bb );
            return nwrite + nw + nh + nfooter;
        }
        public void nonEmptyify() { if (iotree.isEmpty()) put( new DummyBlock() ); }
        
        public void checkLeaks() {
            System.out.format( "BlockTree.checkLeaks()\n" );
            System.out.format( "Generation.iotree -- size:%d\n", iotree.size );
            iotree = null;
            System.gc();
        }

        public void addWrite(Hunker hunker,BlockNode block,Command cmd) {
            if (!block.writ) {
                long mask = hunker.bm >> bitsHeader;
                int nlongs = nwrite + ntitle; // the magic, count and kblock for each block
                if ((nlongs&mask)==0) nheader++;
                nwrite++;
            }
            block.addWrite(cmd);
        }
        public void handle(Hunker hunker) {
            hunker.qrunner.lastgen = gen2;
            hunker.qrunner.dc.scrub();
            hunker.qrunner.sendGen = true;
            if (dropOutstanding) hunker.qrunner.clearack = true;
            for (Task task = completedTasksList; task != null; task = task.listForGen)
                task.postRun(false);
        }

        public BlockNode [] blocks = new BlockNode[1 << bitsIotree];
        public void put(BlockNode block) {
            blocks[iotree.size] = block;
            iotree.put( block.kblock, block );
            if (iotree.size >= blocks.length)
                Simple.softAssert( false, "50%% hash capacity exceeded" );
        }
        public void remove(long kblock) {
            iotree.remove( kblock );
            int ii = 0;
            while (blocks[ii].kblock != kblock) ii++;
            blocks[ii] = blocks[iotree.size];
            blocks[iotree.size] = null;
        }
        public void sort() { 
            Arrays.sort( blocks, 0, iotree.size );
            blocks = Util.dup( blocks, 0, iotree.size );
        }

    }

    public static class BlockTree extends TreeDisk<BlockNode,Void> {
        public int compare(BlockNode v1,BlockNode v2,Void cc) {
            long k1 = v1.kblock, k2 = v2.kblock;
            return Long.signum( k1-k2 );
        }
        public void checkLeaks() {
            System.out.format( "BlockTree.blocks.cache\n" );
            for (Entry<BlockNode> entry : this) entry.val.cache = null;
            System.gc();
            System.out.format( "BlockTree.blocks.cmds\n" );
            for (Entry<BlockNode> entry : this) entry.val.cmds = null;
            System.gc();
            System.out.format( "BlockTree.blocks.writeCache\n" );
            for (Entry<BlockNode> entry : this) entry.val.writeCache = null;
            System.gc();
        }
    }

    public static class ForceCommit extends BlockNode implements Queable {
        { kblock = Long.MAX_VALUE; writ = false; }
        public boolean done = false;
        public void entree(QueRunner qr) {
            qr.dc.submitCache();
            qr.map.put( this );
        }
        public boolean runBlock(Hunker hunker,byte [] data) { return false; }
        // fixme:ssd -- should really wait till the containing map is handle'd
        //   they're in kblock order for now, so good enough
        //   if an elevator-free map is used, ie unsorted, should fix this
        public void handle(Hunker hunker) { done = true; }
        public boolean dontRead() { return true; }
    }
    
    /** a placeholder to allow making the iotree non-empty */
    public static class DummyBlock extends BlockNode {
        { kblock = -1; writ = false; }
        public boolean runBlock(Hunker hunker,byte [] data) { return true; }
        public void handle(Hunker hunker) {}
        public boolean dontRead() { return true; }
    }
    /** 
     *  a class that implements shutdown for the Hunker
     *    fixme -- inhibit adding new tasks and let the existing tasks play out
     */
    public static class Shutdown extends BlockNode implements Queable {
        { kblock = Long.MAX_VALUE; writ = false; }
        long gen;
        public void entree(QueRunner qr) {
            qr.dc.submitCache();
            qr.map.put( this );
            gen = qr.map.genc;
            qr.map.shutdown = true;
        }
        public boolean runBlock(Hunker hunker,byte [] data) { return false; }
        public void postRun(Hunker hunker) throws IOException {
            hunker.runner.finished = true;
        }

        public void handle(Hunker hunker) {
            hunker.qrunner.finished = true;
        }
        public boolean dontRead() { return true; }
    }
    public static int maxcmds = 0;
    public static class BlockNode implements Predicable, Comparable<BlockNode> {
        public long kblock;
        /** the entire block will be written */  public boolean full = false;
        public DynArray.Objects<Command> cmds;
        /** the cached block, ie a priori */
        public CachedBlock cache;
        /** the cache for the final state of the block, ie the written value, ie a posteriori */
        public CachedBlock writeCache;
        public boolean writ;
        public DiskAnomaly diskAnomaly;
        public boolean commit;

        /** is the block really full, ie the whole block will be written and there are no reads */
        public boolean full() { return false; }
        public CachedBlock xcache() { return writeCache==null ? cache:writeCache; }

        public void addWrite(Command cmd) {
            if (!writeCache.ready())
                cmds.add(cmd);
            writ = true;
//            if (cmds.max > maxcmds) maxcmds = cmds.max;
            maxcmds++;
        }
        public void addRead(Command cmd) {
            cmds.add(cmd);
        }
        
        public BlockNode init() {
            cmds = new DynArray.Objects().init( Command.class );
            return this;
        }
        public BlockNode kblock(long kb) { kblock = kb; return this; }
        public boolean dontRead() {
            boolean dont = full()
                    || (writeCache != null && writeCache.ready())
                    || (     cache != null &&      cache.ready());
            return dont;
        }
        /** run the block - executed in the Runner thread */
        public boolean runBlock(Hunker hunker,byte [] data) throws IOException {
            if (full())
                Simple.softAssert(false,"not implemented yet");
            
            if (cache != null && !cache.ready()) {
                Simple.softAssert( cache.data==null );
                cache.data = data;
            }


            // all reads occur at block.cache, then writes are applied
            if (writ && !writeCache.ready()) {
                writeCache.data = Util.dup(cache.data);
                Command.Page b2 = Command.Page.wrap(writeCache.data);
                for (Command cmd : cmds)
                    if (cmd.write()) {
                        cmd.run( (int) (cmd.offset & hunker.bm), b2, hunker,false );
                        cmd.clean();
                    }
                writeCache.data = b2.data;
            }
            if (!writ && commit && cache.data==null) {
                System.out.format( "runBlock:cache.data==null ... %s\n", cache.info() );
            }

            if      (writ) hunker.runner.journal(writeCache.data);
            else if (commit)  doWrite(hunker,cache.data);

            return !writ;
        }
        void doWrite(Hunker hunker,byte [] data) throws IOException {
            if (data==null) Simple.softAssert(false);
            hunker.runner.write( kblock, ByteBuffer.wrap(data) );
            hunker.runner.totalWrites++;
        }
        public void postRun(Hunker hunker) throws IOException {
            if (writ && commit) {
                doWrite(hunker, writeCache.data );
            }
        }

        public void handle(Hunker hunker) {
            if (diskAnomaly != null) {
                System.out.format( "BlockNode.anomaly -- kblock:%d, num\n", kblock, diskAnomaly.numRead );
                Command.print(hunker,true,cmds);
                for (Command cmd : cmds) {
                    Transaction txn = cmd.txn;
                    Task task = txn==null ? null : txn.task;
                    String txt = String.format(
                            "BlockNode.anomaly -- kblock:%d, num:%d, %s, %s, %s\n", 
                            kblock, diskAnomaly.numRead, task, txn, cmd.msg );
                    // fixme::correctiveAction -- what should happen on a disk error ???
                    if (task==null || true) throw rte( null, txt );
                    task.rollback( hunker, true );
                }
                Simple.softAssert(false,"BlockNode.anomaly");
                return;
            }
            
            // writes have already been run and stored in writeCache, just book them
            for (Command cmd : cmds)
                if (!cmd.write()) {
                    int num = --cmd.txn.nr;
                    if (num==0)
                        hunker.qrunner.waiting.put( cmd.txn.task );
                    Simple.softAssert(num >= 0 && cache != null && cache.data != null);
                }
            
            if (writ && writeCache.ready()) Simple.softAssert(cache==null);
            
            CachedBlock bc = writ ? writeCache : cache;
            boolean already = bc==null || bc.ready();
            if (! already) {
                cache.ready = true;
                if (writ) writeCache.ready = true;

                
                BlockNode block = hunker.qrunner.map.iotree.get( bc.kblock );
                if (block != null) block.onReady(hunker,bc.data);
            }
            if (commit)
                hunker.qrunner.dc.commit( xcache() );
            cmds = null;
            cache = writeCache = null;
        }
        /**
         *  the cache backing this block just went "ready" ... replay the cmds, booking reads
         *    remove the block from the tree if there aren't any writes
         *    set the data and ready for the writeCache if needed
         *  note: there can only be 1 cache entry that's not up to date since the pending generation
         *    is held until the current generations Marker is seen, ie all current blocks are handled
         */
        public void onReady(Hunker hunker,byte [] data) {
            DynArray.Objects<Command> c2 = cmds;
            cmds = new DynArray.Objects().init( Command.class );
            
            if (writ) data = Util.dup(data);
            Command.Page b2 = Command.Page.wrap(data);
            for (Command cmd : c2) {
                if (cmd.write()) {
                    cmd.run( (int) (cmd.offset&hunker.bm), b2, hunker,false );
                    cmd.clean();
                    cmds.add(cmd);
                }
                else
                    if (--cmd.txn.nr == 0)
                        hunker.qrunner.waiting.put( cmd.txn.task );
            }
            if (writ) {
                writeCache.setData( b2.data, true );
                cache = null;
            }
            else if (commit)
                cache.setData( b2.data, true );
            else
                hunker.qrunner.map.remove(kblock);
        }

        public int compareTo(BlockNode o) {
            return (int) (kblock - o.kblock);
        }
    }
    public static class CachedBlock extends Listee<CachedBlock> implements Comparable<CachedBlock> {
        public long kblock;
        public byte [] data;
        /** the generation of the latest write or zero for pure reads */
        public long gen = QueRunner.gencUsageMarker(0);
        /** the generation of the latest access */
        public long update = QueRunner.gencUsageMarker(0);
        /** 
         *  indicate the block is live
         *  can use data being non-null, but then updates happen in Runner thread
         *  makes debugging harder / more confusing
         *  fixme:simplicity -- delete and replace with data being non-null
         */
        public boolean ready = false;
        public boolean writ, submit;
        boolean counted;
        /** linked list of cache for this kblock */
        public CachedBlock older, newer;
        public boolean out;

        public static class ListEntry extends Listee<ListEntry> {
            public CachedBlock cb;
            public ListEntry(CachedBlock $val) { cb = $val; }
            public CachedBlock read() { return cb.older; }
            
        }
        /** has the block been initialized ? */
        public boolean ready() { return ready; }
        /** the the key */
        public CachedBlock setKey(long $kblock,long $gen)
            { kblock = $kblock; gen = $gen; return this; }
        /** slurp the data from buf and store it in data (atomically), if setReady set ready true */
        public void slurp(byte[] $data) {
            data = Util.dup($data);
        }
        /** copy src and use it as data */
        public void setData(byte [] $data,boolean $ready) { data = $data; ready = $ready; }
        /** sort by kblock, then gen */
        public int compareTo(CachedBlock o) {
            if (true)
                return Long.signum( kblock - o.kblock );
            return (kblock == o.kblock)
                    ? Long.signum( gen-o.gen )
                    : Long   .signum( kblock - o.kblock );
        }
        public String toString() { return kblock + ":" + gen; }
        public String info() {
            String fmt = "CachedBlock"
                    + "\n\t kblock:%d"
                    + "\n\t data:%s"
                    + "\n\t gen:%d"
                    + "\n\t update:%d"
                    + "\n\t ready:%b"
                    + "\n\t committed:%b"
                    + "\n\t older:%s"
                    + "\n\t newer:%s"
                    + "\n\t out:%b";
            return String.format(fmt,
                    kblock, data, gen, update, ready, submit, older, newer, out );
        }
        public void clear() { data=null; older=newer=null; kblock=gen=update=-1; }
    }



    public static class CT {
        private boolean checkLinks = false;
        public LongHash.Map<CachedBlock> ct = new LongHash.Map().init( Cache.nbb + 2 );
        public void checkLeaks() {
            { checkField(0); System.gc(); }
            { checkField(1); System.gc(); }
            { checkField(2); System.gc(); }
            { checkField(3); System.gc(); }
        }
        public void checkField(int field) {
            String txt = "";
            switch(field) {
                case 0:
                    return;
                case 1:
                    txt = "CacheTree.data";
                    for (CachedBlock entry : ct.values())
                        entry.data = null;
                    break;
                case 2:
                    txt = "CacheTree.listee";
                    for (CachedBlock entry : ct.values())
                        entry.next = entry.prev = null;
                    break;
                case 3:
                    txt = "CacheTree.older";
                    for (CachedBlock entry : ct.values())
                        entry.newer = entry.older = null;
                    break;
            }
            System.out.format( "%s\n", txt );
            System.gc();
            System.gc();
            System.gc();
            System.gc();
            System.gc();
        }
        /** get the cb for kblock that is at least as old as gen - else null */
        CachedBlock get(long kblock,long gen) {
            CachedBlock c2 = ct.get(kblock);
            if (c2==null) return null;
            while (c2.gen > gen)
                c2 = c2.older;
            if (checkLinks)
                Simple.softAssert( c2.older==null || c2.newer==null || c2.older != c2.newer );
            return c2;
        }
        public Iterable<CachedBlock> iter() {
            return ct.values();
        }
        private void delete(CachedBlock c1) {
            if (c1.writ & c1.submit)
                Simple.softAssert(false);
            Simple.softAssert(c1.older==null);
            if (checkLinks)
                Simple.softAssert( c1.older==null || c1.newer==null || c1.older != c1.newer );
            if (c1.newer==null)
                ct.remove(c1.kblock);
            else {
                if (checkLinks)
                    Simple.softAssert(c1.newer.older==c1);
                c1.newer.older = null;
            }
            size--;
            c1.clear();
        }

        public int size;
        private int size() { return size; }

        private void put(CachedBlock cb) {
            CachedBlock c2 = ct.put( cb.kblock, cb );
            size++;
            if (c2==null) return;
            cb.older = c2;
            c2.newer = cb;
        }
    }
    public static class Cache {
        public CT tree = new CT();
        static int nbb = bitsCache + (12-Db4j.blockSize);
        /** ratio of block cache hits for the last decade           */  public double hitRatio = 1.0;
        /** number of cmds that were found in the cache, current decade  */
                                                                        public int hitCount;
        /** number of cmds that tried the cache, current decade     */  public int tryCount;
        /** number of blocks hit by the cache, current decade       */  public int nhit;
        /** maximum allowed size of the cache                       */  public int maxsize = 1 << nbb;
        public Hunker hunker;
        public int incsize = 1 << 8;
        /** list of cached blocks, ordered by update, 
         *    head is the oldest, tail is most recently updated */
        public UpdateTree byUpdate = new UpdateTree();
        /** list of txns ... tail is freshest, head is oldest */
        Listee.Lister<Transaction> txns = new Listee.Lister();
        /** 
         * list of outstanding covered writes, ie there is a txn older than the write
         *  the covering read must be preserved
         * this list is kept in sorted order, according to CachedBlock.gen
         * ie, the generation of the most recent write command
         */
        public Listee.Lister<CachedBlock> covered = new Listee.Lister();
        /** 
         * number of CachedBlock-s as new or newer than the oldest outstanding transaction
         * ie, txns.head
         */
        private int outstanding;
        public int decsize = maxsize;
        /** the newest generation that has been dropped */
        public long cliff = QueRunner.gencUsageMarker(0);
        public boolean check = false;
        public boolean checkScrub = false;
        public boolean checkComplete = true;
        /** list of reads and committed writes that are not outstanding */
        public Listee.Lister<CachedBlock> current = new Listee.Lister();
        /** number of naked uncommitted writes                 */  int ndefoe;
        /** number of naked writes pending commit              */  int ncrusoe;
        /** number of naked covering committed writes          */  int ncovered;
        
        /** number of naked non-covering uncommitted writes    */  int d2;
        /** number of naked non-covering writes pending commit */  int c2;
        /** number of naked covering writes                    */  int ntatler;
        
        /*
         * every CachedBlock should be accounted for in one and only one place
         *   - byUpdate and current -- naked reads and committed non-covering naked writes
         *   - ndefoe and ncrusoe -- for writes that are naked and not yet committed
         *   - covered.size -- covered reads and covered writes, ie covered.older
         *   - ncovered -- covering committed naked write
         * 
         *   sum of those 4 categories should equal the total tree size
         */
        
        
        public class UpdateTree extends TreeDisk<CachedBlock,Void> {
            public int compare(CachedBlock v1,CachedBlock v2,Void cc) {
                return v1.update==v2.update
                        ? Long.compare(v1.kblock,v2.kblock)
                        : Long.compare(v1.update,v2.update);
            }
            public CachedBlock head() { return first().val; }
        }
        public void checkLeaks() {
            System.out.format( "Cache.byUpdate\n" );
            byUpdate = null;
            System.gc();
            System.out.format( "Cache.covered\n" );
            covered = null;
            System.gc();
            System.out.format( "Cache.txns\n" );
            txns = null;
            System.gc();
            tree.checkLeaks();
            System.out.format( "Cache.checkLeaks()\n" );
            System.gc();
            System.out.format( "Cache.tree\n" );
            tree = null;
            System.gc();
        }

        static boolean dd = false;
        public void check() {
            // outstanding:
            //   purely naked and update is no older than oldest txn (semi-optional)
            //   a read or write that is covered by a write that isn't current
            //   a write that hasn't been committed or isn't current
            //   covered reads whose writes aren't current
            boolean stuff = false;
            if (stuff) return;
            boolean dbg = false;
            int count = 0;
            long oldest = oldest();
            long genc = Math.min(oldest,hunker.qrunner.map.genc);
            if (dbg) System.out.format( "----------------------------\n" );
            if (dbg) System.out.format( "---old:%5d, gen:%5d-----\n", oldest, genc );
            int tsize = 0;
            
            int nc=0, nu=0, nd=0, nr=0, no=0, nv=0, nc2=0, nu2=0;

            long t0 = oldest;
            for (Transaction txn : txns) {
                Simple.softAssert(txn.gen0 >= t0);
                t0 = txn.gen0;
            }

            long v0 = oldest;
            for (CachedBlock cb : covered) {
                Simple.softAssert(cb.gen >= v0 & !cb.out & cb.older!=null);
                v0 = cb.gen;
            }

            for (CachedBlock cb : current) {
                nc++;
                Simple.softAssert(cb.update < oldest & !cb.out & cb.newer==null & cb.older==null & !cb.writ);
            }

            long last = oldest;
            for (CachedBlock cb : byUpdate.valueIter) {
                nu++;
                Simple.softAssert(cb.update >= last & cb.out & cb.newer==null & cb.older==null);
                last = cb.update;
            }
            Simple.softAssert(nc==current.size);
            Simple.softAssert(nu==byUpdate.size);
            nc = nu = 0;

            
            for (CachedBlock cb : tree.iter()) {
                while (cb != null) {
                    tsize++;
                    if (cb.out)
                        Simple.softAssert(cb.update >= oldest & cb.newer==null & cb.older==null);
                    if (cb.out)                  nu++;
                    else if (cb.newer==null)
                        if (cb.writ)
                            if (cb.submit)       nr++;
                            else                 nd++;
                        else if (cb.older==null) nc++;
                        else                     no++;
                    else                         nv++;

                    if (dbg)
                        System.out.format(
                            "CB %8d, %5d, %5d -- oldest:%5d, %5d, %5d, wr:%5b, naked:%5b, out:%5b\n",
                            cb.kblock, cb.gen, cb.update, oldest, count, outstanding,
                            cb.writ, cb.newer==null, cb.out );
                    cb = cb.older;
                }
            }
            test(nc,current.size,"current");
            test(nu,byUpdate.size,"update");
//            test(nd,ndefoe,"defoe");
//            test(nr,ncrusoe,"crusoe");
            test(no,ncovered,"ncov");
            test(nv,covered.size,"covered");
            test(tsize,tree.size,"tree");

            test();
        }
        void test(int ci,int cc,String txt) {
            if (ci != cc)
                System.out.format("\nCache.check:%-8s -- meas:%6d, saved:%6d, diff:%6d", txt, ci, cc, ci-cc);
        }
        /** 
         * the generation of the oldest outstanding transaction or possible txn
         * ie a read-only gets the generation of the current map
         */
        public long oldest() {
            long last = useLastgen ? hunker.qrunner.lastgen : hunker.qrunner.map.genc;
            if (txns.head==null) return last;
            else                 return Math.min( last, txns.head.gen0 );
        }
        public void test() {
             Simple.softAssert(tree.size==current.size+byUpdate.size+d2+c2+ntatler+covered.size);
        }

        /** remove txn from the list of outstanding transactions 
         *    and cleanup any now-naked cache entries */
        public void cleanupTxn(Transaction txn) {
            if ( !check && checkScrub ) check();
            if (txn==txns.head)
                Simple.nop();
//            check();
            txns.remove( txn );
            scrub();
        }

        public Cache set(Hunker $hunker) {
            hunker = $hunker;
            return this;
        }
        String info() {
            return String.format("cache: %5d %5d %5d",byUpdate.size,ntatler+covered.size,d2+c2);
        }
        public void dumpTxns() {
            System.out.format( "Hunk:Cache -- %5d, %5d, %5d\n",
                    txns.size, covered.size, tree.size );
            for (Transaction txn : txns)
                System.out.format( "cache.txn -- %s\n", txn );
        }

        public void complete(long gen,long total) {
            int ios = tryCount - hitCount;
            if (ios >= decsize) {
                hitRatio = 1.0 * nhit / tree.size();
                int iosize = hunker.qrunner.map.iotree.size();
                if (Hunker.debug.cache) System.out.format(
                        "CT:done try:%6d, miss:%4d, cache:%4d, io:%4d, ",
                        tryCount, tryCount-hitCount, tree.size(), iosize );
                Transaction txn = txns.head;
                long oldest = txn==null ? -1 : txn.gen0;
                Command cmd = null;
                //      cmd = covered.head.val.val.writs.get(0);
                long oldWrite = cmd==null ? -1 : cmd.gen; // fixme:bogus - printed but otherwise unused
                if (Hunker.debug.cache)
                    System.out.format( "gen:%4d, t0:%4d, w0:%4d, ncover:%4d, out:%4d -- %s\n",
                        gen, oldest, oldWrite, covered.size, outstanding,
                        txn==null || txn.task==null ? null : txn.task
                        );
                nhit = hitCount = tryCount = 0;
            }
            if (check || checkComplete) check();
        }
        /** initialize and insert the cached block into the cache and manage the cache */
        public CachedBlock putCache(long offset,long gen,long update,boolean writ) {
            if (check) check();
            long kblock = offset >> hunker.bb;
            CachedBlock cb = new CachedBlock().setKey( kblock, gen );
            cb.writ = writ;
            if (tree.size() > maxsize) drop();
            tree.put(cb);
            if (cb.older != null) update = Math.max(cb.older.update,update);
            setUpdate(cb,update);
            return cb;
        }
        public int precovered() { 
            return maxsize - byUpdate.size - covered.size - ntatler;
        }
        public int precommit() { 
            return precovered()-d2-c2;
        }
        public int predefoe() { return prestand() + byUpdate.size; }
        public int precrusoe() { return predefoe() + ncrusoe; }
        public int prestand() { return maxsize - tree.size + current.size; }

        public void dump() {
            for (CachedBlock cb : tree.iter())
                System.out.format( "%20s\n", cb );
        }

        /** 
         * check if cmd is in the cache ... if it is, return the CachedBlock, else null 
         * update the statistics if successful
         */
        public CachedBlock getCache(long offset,long gen,long last) {
            tryCount++;
            long kblock = offset >> hunker.bb;
            CachedBlock cache = tree.get(kblock,gen), c2 = cache;
            // fixme -- consistency for eventual writes
            //  a 2nd read could get interleaved writes and be inconsistent with previous read
            //  will bomb out on submit, but could result in unpredictable behavior first
            //  should bomb out (rollback) here
            if (cache==null) return null;
            else {
                hitCount++;
                // always update the latest version
                while (c2.newer != null) c2 = c2.newer;
                update( c2, last );
                return cache;
            }
        }

        void collect(CachedBlock cb) {
            Simple.softAssert(cb.out & cb.newer==null & cb.older==null);
            cb.out = false;
            if (cb.writ) {
                if (cb.submit) c2++;
                else           d2++;
            }
            else current.append(cb);
        }
        
        public long oldestRead() {
            // fixme:read-only -- should track the read-only transactions and preserve
            //   the generation of the map at the time of creation
            long last = useLastgen ? hunker.qrunner.lastgen : hunker.qrunner.map.genc;
            Transaction OldestReadOnlyTxn = null;
//            OldestReadOnlyTxn = txns.head;
            return (OldestReadOnlyTxn==null)
                    ? last
                    :Math.min(last,OldestReadOnlyTxn.gen0);
        }
        /*
         * conditions to drop a covered read or write
         *   - the covering write is at least as old as the oldest possible txn
         *      - the current map's generation (because a new txn could begin)
         *      - the generation of the map when the oldest read-only txn was initialized
         *   - the covered read can't be in use by the Runner map
         *       ie, due to a commit or not yet ready
         *       if the covering write was a member of a completed generation, then the read can't be
         *         used in the Runner map generation
         *     or the read is ready and not pending commit
         */
        
        /** scrub any covering reads that aren't needed anymore, ie the covered write is current */
        public void scrub() {
            long oldest = oldest();
            TreeDisk.Entry<CachedBlock> entry = byUpdate.first(), next;
            
            test();
            for (; entry.real() && entry.val.update < oldest; entry = next) {
                next = entry.next();
                entry.remove(byUpdate);
                collect(entry.val);
                test();
            }
            if (check || checkScrub) check();
            test();

            long genc = oldestRead();
            CachedBlock cb, read;
            while ((cb=covered.head) != null && cb.gen <= genc && (read=cb.older).ready()) {
                if (read.writ & read.submit)
                    // still in use ... a commit is pending
                    break;
                uncover(covered.pop());
            }
            if (check || checkScrub) check();
            test();
        }
        void check(CachedBlock cb) {
            Generation pending = hunker.pending;
            boolean overflow = pending != null && cb.update >= pending.genc;
            // fixme -- this is just a sanity check, debugging aid ... delete it once stable
            int ii = 0;
            for (Generation map = hunker.pending; ii < 2; map = hunker.qrunner.map, ii++) {
                if (map==null) continue;
                BlockNode block = map.iotree.get(cb.kblock);
                if (block != null) {
                    CachedBlock c1=block.cache, c2=block.writeCache;
                    if (c1==null & c2==null)
                        ;
                    else if (c1==cb | c2==cb) {
                        System.out.format( "gen:%d, up:%d, map:%d\n", cb.gen, cb.update, map.genc );
                        System.out.format( "block.commit:%b\n",
                                block.commit );
                        Simple.softAssert(false, "remove.inuse -- %b, kblock:%d", block.cache==cb, block.kblock);
                    }
                    else
                        Simple.softAssert(cb.newer != null, "remove.inuse -- kblock:%d", block.kblock);
                }
            }
            if (overflow & false)
                Simple.softAssert(false);
        }
        private void removeNotReady(CachedBlock cb) {
            Simple.softAssert(false,"not yet implemented"); // fixme !!!
        }
        /**
         * if the cache is over capacity, drop the excess and update the QueRunner/txns
         * returns the newest generation that was effected
         * QRT-only (ie, queRunnerThread)
         */
        public void drop() {
            CachedBlock cb = current.pop();
            if (cb==null)
                throw new DropOutstandingException();
            cliff = cb.update;
            check(cb);
            Simple.softAssert(!cb.out & cb.newer==null & cb.older==null & !cb.writ & cb.update < oldest());
            tree.delete(cb);
            cb.kblock = -2;
            if (check) check();
        }
        void uncover(CachedBlock write) {
            if (write.newer==null) ntatler--;
            boolean ww = write.writ, wo = write.out;
            int cs = current.size, us = byUpdate.size, ts = tree.size, nc = ncovered;
            if (! write.writ & write.newer==null) {
                // committed and no longer covering ... readify
                ncovered--;
            }
            // otherwise still awaiting commit, so accounted for in ndefoe or ncrusoe

            CachedBlock read = write.older;
            write.older = null;
            
            // transition to non-covering ... if still naked and uncommitted
            if (write.newer != null);
            else if (write.writ) {
                if (write.update >= oldest()) addToUpdate(write);
                else if (write.submit) c2++;
                else                   d2++;
            }
            else addRead(write);

            Simple.softAssert(read != null && write.update >= read.update);
            check(read);
            tree.delete(read);
            read.kblock = -3;
            test();
        }
        /** remove cb from the cache ... cb must be a preRead that was allocated */
        void cancelPreread(CachedBlock cb) {
            Simple.softAssert(cb.writ==false & cb.newer==null & cb.older==null & cb.out);
            byUpdate.remove(cb);
            check(cb);
            tree.delete(cb);
            cb.kblock = -4;
            test();
        }
        void undefoe(CachedBlock cb) {
            if (cb.submit) c2--;
            else           d2--;
        }
        // fixme -- merge undefoe into removeRead
        void removeRead(CachedBlock cb) {
            // a committed write or a read ... can't be a covered read
            if (cb.out) byUpdate.remove(cb);
            else         current.remove(cb);
            cb.out = false;
        }
        void addToUpdate(CachedBlock cb) {
            byUpdate.put(cb);
            cb.out = true;
        }
        void addRead(CachedBlock cb) {
            if (cb.update >= oldest())
                addToUpdate(cb);
            else {
                Simple.softAssert(!cb.writ & cb.newer==null & cb.older==null & !cb.out);
                current.append(cb);
            }
        }
        /** update the time-to-live info for this block */
        public void update(CachedBlock cb,long update) {
            Simple.softAssert(cb.newer==null);
            Simple.softAssert(update >= oldest());
            if (update <= cb.update) {
                Simple.softAssert(cb.out | cb.newer != null | cb.older != null,
                        "update should be outstanding, so should be either in byUpdate or covered" );
                return;
            }
            if (check) check();
            
            // if in either current or defoe then move to byUpdate
            // if in covered (in either role) then let it be
            if (cb.newer==null & cb.older==null) {
                if (cb.out | !cb.writ) removeRead(cb);
                else                   undefoe   (cb);
                cb.update = update;
                addToUpdate(cb);
            }
            else
                cb.update = update;
            if (check) check();
        }
        /** 
         * set the time-to-live and append to the list of cache ordered by update
         * assumption: cb is not yet in byUpdate
         */
        public void setUpdate(CachedBlock cb,long update) {
            Simple.softAssert(cb.newer==null & update >= oldest());
            cb.update = update;
            if (cb.writ)         ndefoe++;
            else                 addToUpdate(cb);
        }
        /** handle the accounting for a newly covered cb, submitting the covering write if needed */
        public void cover(CachedBlock cb,boolean submit) {
            if (cb.writ) {
                if (cb.submit)          ncrusoe--;   // commit pending
                else                     ndefoe--;   // uncommitted
                if (cb.older==null) {
                    if (cb.out) removeRead(cb);
                    else        undefoe   (cb);
                }
            }
            else if (cb.older != null) ncovered--;   // a write, committed and covering
            else
                removeRead(cb);                      // uncovered and committed (read or write)
            covered.append(cb.newer);

            if (cb.older==null) ntatler++;
            
            if (submit) {
                ndefoe--;
                ncrusoe++;
                cb.newer.submit = true;
                if (cb.submit & cb.writ) cb.submit = false;
            }
        }
        private void commit(CachedBlock cb) {
            Simple.softAssert(cb.gen >= 0);
            ncrusoe--;
            if (cb.newer==null & cb.older==null & !cb.out) c2--;
            cb.writ = false;
            if (cb.newer != null)                ncrusoe++; // covered so already counted as a covered read
            else if (cb.older != null)          ncovered++; // covering
            else if (cb.out)                              ;
            else addRead(cb);                               // current or outstanding
            test();
        }
        public void submitCache() {
            int nd = ndefoe, nd2 = d2;
            ncrusoe += ndefoe;
            ndefoe = 0;
            c2 += d2;
            d2 = 0;
            int num=0, n2=0;
            Generation map = hunker.qrunner.map;
            map.commit = true;
            for (CachedBlock cb : tree.iter()) {
                Simple.softAssert(cb.newer==null);
                if (cb.writ & !cb.submit) {
                    BlockNode block = hunker.qrunner.getBlock( cb.kblock, cb, map );
                    block.commit = true;
                    cb.submit = true;
                    num++;
                    if (cb.older==null & cb.newer==null & !cb.out) n2++;
                }
            }
            Simple.softAssert(nd==num & n2==nd2,
                    "Cache.submit -- ndefoe:%d, meas:%d, diff:%d, crusoe:%d, d2:%d %d\n",
                    nd,num,nd-num,ncrusoe,n2,nd2);
            test();
        }
        private void dropNotReady() {
            for (CachedBlock cb : tree.iter()) {
                for (CachedBlock c2; cb != null; cb = c2) {
                    c2 = cb.older;
                    if (! cb.ready())
                        removeNotReady( cb );
                }
            }
        }
    }
    






    public interface Queable {
        /** add the contents to the iotree */
        public void entree(QueRunner qr);
    }

    public interface Predicable {
        /** Runner has completed processing */
        public void handle(Hunker hunker);
    }
    /** a cooperatively latch a page, ie first caller is owner, later callers are deferred */
    public static class Latch {
        Latch next;
        int kpage;
        public DynArray.Objects<Db4j.Task> tasks;
        /** initialize storage if needed */
        public void init() { if (tasks==null) tasks = DynArray.Objects.neww(Db4j.Task.class ); }
        /** restart the deferred tasks */
        public void restart(Hunker hunker) {
            if (tasks != null) {
                for (Db4j.Task task : tasks) {
                    if (Hunker.debug.reason) task.pure().reason( "BlockNode.restartLatches" );
                    hunker.qrunner.backlog.put(task);
                }
            }
            hunker.qrunner.latchMap.remove( kpage );
        }
    }


    public static abstract class Rollbacker extends Listee<Rollbacker> {
        public abstract void runRollback(Transaction txn);
    }
    public static class ClosedException extends RuntimeException {}
    public static class RestartException extends RuntimeException {}
    public static class DropOutstandingException extends RuntimeException {}

    public static class Transaction extends Listee<Transaction> {
        /** 
         * commands to be executed when the transaction is entreed, 
         * ie writes only, since reads can be sent immediately
         * a linked list using Command.next
         * most recently inserted command as tail, ie in forward order
         */
        public Command writs;
        /** the tail of the (write) cmds */
        public Command writsTail;
        /** the last command to be submitted, ie the set [reads,readsTail) is outstanding */
        public Command readsTail;
        /** the number of reads and writes */
        public int nreads, nwrits;
        /** the number of previously submitted reads */
        public int ksub;
        /** monotonic unique id, set in registration to the qrunner genc */
        public long gen0 = QueRunner.gencUsageMarker(0);
        public int ncomplete;
        public boolean inuse, committed;
        /** 
         * the transaction was dropped or
         * the commit failed ... the queRunner detected a change to a dependent read
         * roll it back and retry
         */
        // fixme::configable -- allow txn to relax consistency reqs
        public boolean rollback;
        public boolean restart;
        public Listee.Lister<Rollbacker> rollbackers;
        DynArray.Objects<Cleanable> cleaners;

        public Hunker hunker;
        public Task task;
        /** a list of 1-generation latches */
        public Latch latches;
        public boolean uptodate;
        public PreRead p1;
        public boolean readonly;
        public int nr;

        public void addRollbacker(Rollbacker rb) {
            if (rollbackers==null) rollbackers = new Listee.Lister();
            rollbackers.append( rb );
        }
        public void addCleaner(Cleanable cleaner) {
            if (cleaners==null) cleaners = new DynArray.Objects().init( Cleanable.class );
            cleaners.add(cleaner);
        }

        public Transaction set(Hunker $hunker) { hunker = $hunker; return this; }

        /** add a latch for kpage, ie if that page isn't held become the owner, otherwise defer */
        public void addLatch(int kpage) {
            Latch cmd = new Latch();
            cmd.kpage = kpage;
            cmd.next = latches;
            latches = cmd;
        }

        /**
         * add the cmd to the transaction
         * for writes, just add them to the transaction
         * for reads, see if the read can be satisfied by the current list of writes
         * if not, at it to the io que immediately
         */
        public void add(Command cmd) {
            uptodate = false;
            cmd.txn = this;
            if (rollback) throw new ClosedException();
            // fixme::configurability -- allow the txn to specify that inconsistency is ok
            if (writs==null) {                      writsTail = writs = cmd; }
            else            { writsTail.next = cmd; writsTail         = cmd; }
            if (cmd.write()) nwrits++;
            else nreads++;
        }
        public void cleanse(long kblock) {
            final int bb = hunker.bb;
            Command c1 = writs;
            writs = null;
            for (Command cmd = c1; cmd != null; cmd = cmd.next) {
                if (cmd.write() & (cmd.offset >> bb)==kblock) nwrits--;
                else if (writs==null)                writs = writsTail = cmd;
                else                 { writsTail.next = cmd; writsTail = cmd; }
            }
        }
        void clear() {
            ksub = nreads = nwrits = 0;
            writs = readsTail = null;
        }
        public void overWritten() {
            QueRunner qr = hunker.qrunner;
            for (Command cmd = writs; !rollback && cmd != null; cmd = cmd.next)
                if (!cmd.write())
                    rollback = qr.overwritten(cmd,writs);
        }

        /** latch all latches, return true if a latch has failed and need to be deferred */
        public boolean checkLatch(QueRunner qr) {
            if (latches==null) return false;
            overWritten();
            if (rollback) return false;
            Latch cmd;
            for (cmd = latches; cmd != null; cmd = cmd.next) {
                if (cmd.tasks != null) continue;
                Latch owner = qr.latchMap.get( cmd.kpage );
                if (owner==null) continue;
                if (Hunker.debug.reason)
                    task.pure().reason(
                            "latch already held ... appending block:%s, owner:%s", cmd.kpage, owner );
                if (owner.tasks != null) for (Task t2 : owner.tasks) {
                    if (t2==task) {
                        Simple.nop();
                        task.reason();
                        Simple.softAssert( false );
                    }
                }
                latches = null;
                owner.tasks.add(task);
                task.rollback(hunker,false);
                cleanup(); // fixme -- isn't this already happening in rollback() ???
                return true;
            }
            if (Hunker.debug.reason)
                task.pure().reason( "successfully latching" );
            for (cmd = latches; cmd != null; cmd = cmd.next) {
                cmd.init();
                qr.latchMap.put( cmd.kpage, cmd );
            }
            return false;
        }
        public void cleanup() {
            hunker.qrunner.dc.cleanupTxn( this );
            unlatch();
            if (cleaners != null)
                for (Cleanable cleaner : cleaners) cleaner.clean();
            cleaners = null;
        }
        public void unlatch() {
            for (Latch latch = latches; latch != null; latch = latch.next)
                latch.restart(hunker);
            latches = null;
        }
        public Command getTail() { return readsTail==null ? writs:readsTail.next; }
        public boolean entreeReads(QueRunner qr) {
            if (gen0 <= qr.dc.cliff)
                rollback = true;
            
            if (rollback)
                return false;
            else {
                boolean complete = true;
                Command tail = getTail(), missing = null;
                for (Command cmd = tail; cmd != null; cmd = cmd.next) {
                    if (cmd.write()) continue;
                    if (cmd.done()) continue;
                    // fixme -- for readonly, use the gen of the current map
                    //   otherwise, need to fix runCache to replay writes to the correct gen
                    //   btw readonly is totally untested atm !!!
                    cmd.gen = readonly ? gen0 : qr.genc;
                    if (complete) missing = cmd;
                    // fixme::infrastructure -- replay writs over cached values
                    PreRead pr = qr.preRead( cmd, writs );
                    if (pr != null) {
                        complete = false;
                        pr.next = p1;
                        p1 = pr;
                    }
                    else ksub++;
                }
                    
                if (debug.eeeread && !complete && missing != null) {
                    long kb = missing.offset >> qr.hunker.bb;
                    System.out.format( "eeeread: %d, %s, %s, %s\n", kb, missing, task, missing.msg );
                    Simple.nop();
                }
                if ( complete ) { readsTail = writsTail; }
                return complete;
            }
        }
        public void cancelReads() {
            for (PreRead pr = p1; pr != null; pr = pr.next)
                hunker.qrunner.cancelRead( pr );
            p1 = null;
        }
        public void commitReads() {
            for (PreRead pr = p1; pr != null; pr = pr.next, nr++)
                hunker.qrunner.commitRead( pr );
            p1 = null;
        }

        // force some submits to yield instead ie don't try to entree the read
        //   for testing to judge the cost of yield
        static int fakeCount = 0, fakeMask = 0x0;
        
        /** submit the transaction - return true if a yield is required */
        public boolean submit() {
            if ((fakeCount++ & fakeMask) > 0)
                return true;
            uptodate = true;
            if (! inuse) hunker.qrunner.register( this );
            boolean found = entreeReads( hunker.qrunner );
            if (Hunker.debug.reason) task.pure().reason( "txn.submit -- found:%b", found );
            if (found)
                return false;
            else {
                boolean debugIt = false;
                if (debugIt)
                    found = entreeReads( hunker.qrunner );
                return true;
            }
        }
        public void submitYield() throws Pausable {
            if (submit())
                kilim.Task.yield();
        }

        /** returns true if the transaction was handled by cache */
        public boolean entree(QueRunner qr) {
            if (! inuse) qr.register( this );
            boolean deferred = (gen0 != qr.genc);
            if (!committed) {
                if (! uptodate) {
                    boolean found = entreeReads(qr);
                    if (found) return true;
                }
                boolean latched = false;
                if (deferred) checkLatch( qr );
                else latches = null;
                if (rollback || latched) return false;
                commitReads();
                cleanOldReads();
                return false;
            }
            uptodate = true;
            if (nwrits==0) return true;

            if (gen0 <= qr.dc.cliff)
                rollback = true;

            Generation map = qr.map;
            Simple.softAssert( ! map.journalFull );
            long space = qr.journalSpace();
            long current = map.journalBound(hunker,nwrits);
            long pd = qr.dc.predefoe();
            int margin = 1024;
            if ((current+margin > .5*space) && ! map.commit) {
                System.out.format( "Cache:premit -- %8d %8d, pd:%8d +/- %5d\n",
                        current, space, pd, qr.dc.precrusoe() );
                qr.dc.submitCache();
            }
            if (current+margin > space) map.journalFull = true;
            if (current        > space | nwrits        > pd) { rollback = true; return false; }
                
            int avail = qr.dc.prestand();
            if (nwrits > avail)
                throw new DropOutstandingException();
            
            if (deferred) 
                overWritten();
            if (rollback) return false;
            try {
                Simple.softAssert(qr.genc < GENC_MAX_VALUE);
                qr.genc++;
                nr = 0;
                int nc = 0;
                for (Command cmd = writs; cmd != null; cmd = cmd.next, nc++) {
                    if (cmd instanceof Command.Init)
                        qr.handleInit( (Command.Init) cmd );
                    else if (cmd.write()) {
                        cmd.gen = qr.genc; // fixme - appears to be unused
                        qr.handleWrite( cmd );
                        nr++;
                    }
                    else cmd.clean();
                }
                if (task != null && task.postRun(true)) {
                    task.listForGen = qr.map.completedTasksList;
                    qr.map.completedTasksList = task;
                }
            }
            catch (DropOutstandingException ex) {
                ex.printStackTrace();
                Simple.softAssert(false, "DropOutstanding occurred during write ... should never happen");
            }
            return true;
        }

        public String toString() {
            return String.format( "Txn -- %5dr, %5dw, gen%5d", nreads, nwrits, gen0 );
        }
        public void cleanOldReads() {
            Command tail = getTail();
            for (Command cmd = writs; cmd != tail; cmd = cmd.next)
                if (! cmd.write())
                    cmd.clean();
        }
    }

    public static class TaskTree extends TreeDisk<Task,Void> {
        public Task get(final int $id) {
            Task task = new DummyTask();
            task.id = $id;
            return get( task );
        }
        public int compare(Task v1,Task v2,Void cc) { return Integer.signum( v1.id-v2.id ); }

        private Task pop() {
            Entry<Db4j.Task> first = first();
            deleteEntry( first );
            return first.val;
        }
    }

    public static class OldestTask extends Task {
        public Task oldest;
        public void task() throws Pausable {}
        public void runTask(Hunker hunker) {
            oldest = hunker.qrunner.tasks.head;
            hunker.cleanupTask( this );
        }
        public boolean done() { return oldest != null; }
    }


    // if weaving is disabled and everything can fit in cache, can run without kilim
    //   disable weaving in build.xml
    
    /** use an exception instead of yield to restart tasks */
    public static boolean useRestart = false;
    public static void restart() {
        if (useRestart) throw new Db4j.RestartException();
    }
    private static class DummyKilimTask extends kilim.Continuation.FakeTask {
        private static kilim.Fiber.MethodRef runnerInfo
                = new kilim.Fiber.MethodRef(Task.Kask.class.getName(),"re");
        kilim.Fiber.MethodRef getRunnerInfo() {
            return runnerInfo;
        }
    }
    
    private static class WrapperException extends RuntimeException {
        WrapperException(Exception cause) { super(cause); }
    }

    
    /** 
     * an abstract task
     * task() will get called once for each set of reads
     *   then once to write (even if no writes occur)
     *   and then once after the commit
     */
    public abstract static class Task extends Listee<Task> implements Queable {
        public int id;
        Status status = Status.None;
        public Transaction tid;
        public Kask kask;
        public Task listForGen;
        private boolean done, alive = true;
        static kilim.Task dummyKilimTask = new DummyKilimTask();
        int dogyears;
        RuntimeException ex;

        /**
         *  an optional user method that is run once (or twice) after task completion
         *    once during entree() immediately after writes have been run
         *      ie, we've been committed to memory and won't be rolled back
         *    and if the initial run returns true
         *      a 2nd time after the txn is committed to disk
         *  pre: the first invocation, ie immediately after task completion
         *  return true to indicate that the 2nd run should occur
         */
        // fixme -- why use the completedtasklist instead of just counting all the completed writes ???
        public boolean postRun(boolean pre) { return false; }
        
        public class Kask {
            private kilim.Fiber fiber = new kilim.Fiber(dummyKilimTask);
            public boolean re() throws kilim.NotPausable {
                boolean tasking = false;
                try {
                    fiber.begin();
                    tasking = true;
                    task( fiber );
                }
                catch (kilim.NotPausable ex) {}
                // fixme - should only catch task exceptions, not fiber
                catch (RuntimeException kex) { if (tasking) ex = kex; }
                catch (Exception        kex) { if (tasking) ex = new WrapperException(kex); }
                return ex==null ? fiber.end() : false;
            }
        }
        public Task offer(Hunker hunker) { return hunker.offerTask(this); }
        public Task place(Hunker hunker) { offer(hunker); this.await(10); return this; }
        public void await(int delay) { while (! done()) Simple.sleep(delay); }
        public boolean done() {
            if (ex != null) throw ex;
            return done && !alive;
        }
        public Exception getEx() {
            return ex instanceof WrapperException ? ((Exception) ex.getCause()) : ex;
        }
        public void yield() throws Pausable {
            boolean yieldRequired = tid.submit();
            if (yieldRequired)
                kilim.Task.yield();
        }
        public void task(kilim.Fiber fiber) throws Exception {}
        public abstract void task() throws Pausable, Exception;
        public void clear() {
            kask = null;
            tid = null;
            status( Status.None );
        }
        public void init2() { done = false; alive = true; kask = new Kask(); }
        public void init(Hunker hunker) {
            init2();
            tid = hunker.getTransaction();
            tid.task = this;
            status = Status.Init;
            dogyears = 0;
            hunker.qrunner.state.addedTasks++;
            if (Hunker.debug.reason) pure().reason( "task.init" );
        }

        public void preRun(Hunker hunker) {
            if (!tid.committed) {
                if (hunker.qrunner.clearout) {
                    rollback(hunker,true);
                    return;
                }
                if (!tid.readonly) tid.overWritten();
                
                boolean found = false;
                if (!tid.rollback) found = tid.entreeReads( hunker.qrunner );
                if (tid.rollback) {
                    rollback( hunker, true );
                    return;
                }
                else if (!found) {
                    Simple.spinDebug( false, "hunker.preRun -- read not found:%s\n", this );
                    found = tid.entreeReads( hunker.qrunner );
                }
            }
            runTask(hunker);
        }
        public boolean fakeTask() throws kilim.NotPausable {
            try { task(); }
            catch (kilim.Pausable ex) { throw rte(ex,"should never happen"); }
            catch (RestartException ex) { return true; }
            catch (RuntimeException kex) { ex = kex; }
            catch (Exception        kex) { ex = rte(kex); }
            return false;
        }
        
        public void runTask(Hunker hunker) {
            status( Status.Runn );
            boolean found = true;
            boolean defer = false;
            try {
                while (found) {
                if (Hunker.debug.reason) pure().reason( "runTask" );
                tid.uptodate = false;
                if (!done) {
                    if (useRestart) {
                        done = true;
                        defer = fakeTask();
                    }
                    else done = kask.re();
                    if (ex != null) {
                        rollback(hunker,false);
                        postRun(false);
                        return;
                    }
                    if (done) kask = null;
                    if (tid.restart) tid.task = null;
                    if (!done && !tid.rollback && !tid.restart && bitsCache >= 16)
                        if (printNotDone)
                            System.out.println( "NotDone: " + this );
                }
                alive = ! tid.committed;
                if (Hunker.debug.reason)
                    pure().reason( "runTask.resolve -- done:%b, alive:%b", done, alive );
                if (alive) {
                    // fixme -- should have a programmatic way of distinguishing
                    //   between reads and commit
                    if (tid.nreads==tid.ksub && tid.p1==null)
                        tid.committed = true;
                    // fixme - if we're rolledback do we really want to entree first ???
                    found = tid.entree( hunker.qrunner );
                    if (tid==null) return;                                       // the task has been rolled back
                    if (! found && ! tid.rollback) {
                        if (Hunker.debug.reason) pure().reason( "runTask.moveToBlock" );
                        status( Status.Blok );
                    }
                }
                if (tid.rollback || defer || tid.restart) {
                    rollback(hunker,true);
                    return;
                }
                if (tid.committed && !done)
                    System.out.println( "runTask.deferred ... " + this );
                if (alive && tid.committed) tid.cleanup();
                if (done && !alive) {
                    if (tid.nwrits==0) postRun(true);
                    hunker.cleanupTask( this );
                    if (Hunker.debug.reason) pure().reason( "runTask.finished" );
                    return;
                }
                }
                dogyears++;
            }
            catch (DropOutstandingException ex) {
                if (Hunker.debug.reason) pure().reason( "runTask.dropOutstanding: " + ex.toString() );
                // fixme -- ensure that we're not in the middle of a write !!!
                rollback( hunker, true );
                throw ex;
            }
            /* the task has been deferred and the deferree will handle restarting it */
            catch (ClosedException ex) {
                System.out.println( "Closed: " + this );
                if (Hunker.debug.reason) pure().reason( "runTask.closed: " + ex.toString() );
                rollback( hunker, true );
            }
            // not obvious what should be done with an exception in production
            //   but for debugging during developing, it's convenient to be able to attach
            //   and continue where we left off, uncomment the following ...
            //        catch (RuntimeException ex) {
            //            boolean stop = true;
            //            while (stop) {
            //                System.out.format( "Task3.runTask -- exception: %s, %s\n", ex, this );
            //                Simple.sleep( 1000 );
            //            }
            //            throw ex;
            //        }
        }
        public void rollback(Hunker hunker,boolean restart) {
            status( Status.Roll );
            // fixme -- cleanup txn ???
            if (! tid.restart)
                hunker.qrunner.nback++;
            if (Hunker.debug.reason) pure().reason( "Transaction.handle.rollback" );
            if (tid.rollbackers != null)
                for (Rollbacker rb : tid.rollbackers) rb.runRollback(tid);
            tid.cancelReads();
            tid.cleanup();
            clear();
            if (Hunker.debug.checkTasksList) {
                Task old = hunker.qrunner.backlog.get( this );
                if (old != null)
                    Simple.softAssert(old==null);
            }
            if (restart) hunker.qrunner.backlog.put( this );
            hunker.qrunner.state.completedTasks++;
        }
        public String toString() {
            return super.toString() + ":" + id;
        }
        public void reason(Reason reason) {}
//        public <TT> void reason(TT reason) { reason( new ReasonObject().set(reason) ); }
        public void reason(String fmt,Object ... args) {
//            ReasonString rs = new ReasonString();
//            rs.set( String.format(fmt,args) );
//            reason(rs);
        }
        /** print the reasons to stdout */
        public void reason() {}
        public Task pure() { return silentTask; }

        public void entree(QueRunner qr) {
            id = qr.taskID++;
            qr.tasks.append( this );
            init(qr.hunker);
            runTask( qr.hunker );
        }
        void status(Status next) {
            if (!checkStatus) return;
            boolean found = status.next(next);
            if (!found) {
                System.out.format( "Task.Status -- task:%s\n", this );
                reason();
                Simple.softAssert( false, "Task.Status: next:%s, this:%s", next, status );
            }
            status = next;
        }
    }
    public static class DummyTask extends Task { public void task() throws Pausable {} }
    public static class Reason extends Listee<Reason> {}
    public static class ReasonObject<TT> extends Reason {
        TT object;
        public ReasonObject<TT> set(TT $obj) { object = $obj; return this; }
        public String toString() { return object.toString(); }
    }
    public static class ReasonString extends Reason {
        String txt;
        public ReasonString set(String $txt) { txt = $txt; return this; }
        public String toString() { return txt; }
    }
    public static Task silentTask = new DummyTask();
    public static class DiskAnomaly {
        int numRead;
    }
    enum Status {
        None, Init, Detr, Roll, Runn, Blok;
        boolean next(Status next) {
            switch (next) {
                case Init: return check(None,Roll);
                case Runn: return check(Init,Blok);
                case Detr: return check(Runn,Blok);
                case Roll: return check(Runn,Blok);
                case Blok: return check(Runn);
                case None: return check(Roll);
            }
            throw rte(null,"not reachable");
        }
        void require(Status ... tests) {
            boolean found = check(tests);
            Simple.softAssert( found );
        }
        boolean check(Status ... tests) {
            boolean found = false;
            for (Status test : tests) found |= (this==test);
            return found;
        }
    }

    public static class Demo {
        public static void main(String [] args) throws Exception {
            DemoHunker.main(args);
        }
    }
    
}




/*
 * jconsole plugin for watching thread cpu usage
 * http://lsd.luminis.eu/en/new_version_topthreads_jconsole_plugin/
 * 
 * 
 * 
 */