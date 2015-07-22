package org.ethereum.db;

import org.ethereum.core.Block;
import org.ethereum.datasource.mapdb.MapDBFactory;
import org.ethereum.datasource.mapdb.Serializers;
import org.ethereum.util.CollectionUtils;
import org.mapdb.DB;
import org.mapdb.Serializer;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Mikhail Kalinin
 * @since 09.07.2015
 */
public class BlockQueueImpl implements BlockQueue {

    private final static String STORE_NAME = "blockqueue";
    private final static String HASH_SET_NAME = "hashset";
    private MapDBFactory mapDBFactory;

    private DB db;
    private Map<Long, Block> blocks;
    private Set<byte[]> hashes;
    private List<Long> index;

    private boolean initDone = false;
    private final ReentrantLock initLock = new ReentrantLock();
    private final Condition init = initLock.newCondition();

    private final ReentrantLock takeLock = new ReentrantLock();
    private final Condition notEmpty = takeLock.newCondition();

    @Override
    public void open() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    initLock.lock();
                    db = mapDBFactory.createTransactionalDB(dbName());
                    blocks = db.hashMapCreate(STORE_NAME)
                            .keySerializer(Serializer.LONG)
                            .valueSerializer(Serializers.BLOCK)
                            .makeOrGet();
                    hashes = db.hashSetCreate(HASH_SET_NAME)
                            .serializer(Serializer.BYTE_ARRAY)
                            .makeOrGet();
                    index = new ArrayList<>(blocks.keySet());
                    sortIndex();
                    initDone = true;
                    init.signalAll();
                } finally {
                    initLock.unlock();
                }
            }
        }).start();
    }

    private String dbName() {
        return String.format("%s/%s", STORE_NAME, STORE_NAME);
    }

    @Override
    public void close() {
        initLock.lock();
        try {
            awaitInit();
            db.close();
            initDone = false;
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public void addAll(Collection<Block> blockList) {
        initLock.lock();
        try {
            awaitInit();
            takeLock.lock();
            try {
                synchronized (this) {
                    List<Long> numbers = new ArrayList<>(blockList.size());
                    Set<byte[]> newHashes = new HashSet<>();
                    for (Block b : blockList) {
                        if(!index.contains(b.getNumber())) {
                            blocks.put(b.getNumber(), b);
                            numbers.add(b.getNumber());
                            newHashes.add(b.getHash());
                        }
                    }
                    hashes.addAll(newHashes);
                    index.addAll(numbers);
                    sortIndex();
                }
                db.commit();
                notEmpty.signalAll();
            } finally {
                takeLock.unlock();
            }
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public void add(Block block) {
        initLock.lock();
        try {
            awaitInit();
            takeLock.lock();
            try {
                synchronized (this) {
                    if(index.contains(block.getNumber())) {
                        return;
                    }
                    blocks.put(block.getNumber(), block);
                    index.add(block.getNumber());
                    hashes.add(block.getHash());
                    sortIndex();
                }
                db.commit();
                notEmpty.signalAll();
            } finally {
                takeLock.unlock();
            }
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public Block poll() {
        initLock.lock();
        try {
            awaitInit();
            Block block = pollInner();
            db.commit();
            return block;
        } finally {
            initLock.unlock();
        }
    }

    private Block pollInner() {
        synchronized (this) {
            if (index.isEmpty()) {
                return null;
            }

            Long idx = index.get(0);
            Block block = blocks.get(idx);
            blocks.remove(idx);
            hashes.remove(block.getHash());
            index.remove(0);
            return block;
        }
    }

    @Override
    public Block peek() {
        initLock.lock();
        try {
            awaitInit();
            synchronized (this) {
                if(index.isEmpty()) {
                    return null;
                }

                Long idx = index.get(0);
                return blocks.get(idx);
            }
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public Block take() {
        initLock.lock();
        try {
            Block block;
            while (null == (block = pollInner())) {
                notEmpty.awaitUninterruptibly();
            }
            db.commit();
            return block;
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public int size() {
        initLock.lock();
        try {
            awaitInit();
            return index.size();
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        initLock.lock();
        try {
            awaitInit();
            return index.isEmpty();
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public void clear() {
        initLock.lock();
        try {
            awaitInit();
            synchronized(this) {
                blocks.clear();
                hashes.clear();
                index.clear();
            }
            db.commit();
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public List<byte[]> filterExisting(final Collection<byte[]> hashList) {
        initLock.lock();
        try {
            awaitInit();
            return CollectionUtils.selectList(hashList, new CollectionUtils.Predicate<byte[]>() {
                @Override
                public boolean evaluate(byte[] hash) {
                    return !hashes.contains(hash);
                }
            });
        } finally {
            initLock.unlock();
        }
    }

    @Override
    public Set<byte[]> getHashes() {
        initLock.lock();
        try {
            awaitInit();
            return hashes;
        } finally {
            initLock.unlock();
        }
    }

    private void awaitInit() {
        if(!initDone) {
            init.awaitUninterruptibly();
        }
    }

    private void sortIndex() {
        Collections.sort(index);
    }

    public void setMapDBFactory(MapDBFactory mapDBFactory) {
        this.mapDBFactory = mapDBFactory;
    }
}