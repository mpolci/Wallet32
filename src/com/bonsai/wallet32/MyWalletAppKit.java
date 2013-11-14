// Copyright (C) 2013  Bonsai Software, Inc.
// 
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
// 
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bonsai.wallet32;

import android.annotation.SuppressLint;
import com.google.bitcoin.core.*;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.SPVBlockStore;
import com.google.bitcoin.store.WalletProtobufSerializer;
import com.google.common.util.concurrent.AbstractIdleService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * <p>Utility class that wraps the boilerplate needed to set up a new SPV bitcoinj app. Instantiate it with a directory
 * and file prefix, optionally configure a few things, then use start or startAndWait. The object will construct and
 * configure a {@link BlockChain}, {@link SPVBlockStore}, {@link Wallet} and {@link PeerGroup}. Startup will be
 * considered complete once the block chain has fully synchronized, so it can take a while. Once complete, you can
 * go ahead and add the listeners you need to the underlying objects.</p>
 *
 * <p>Note that {@link #startAndWait()} can throw an unchecked {@link com.google.common.util.concurrent.UncheckedExecutionException}
 * if anything goes wrong during startup - you should probably handle it and use {@link Exception#getCause()} to figure
 * out what went wrong more precisely. Same thing if you use the async start() method.</p>
 */
public class MyWalletAppKit extends AbstractIdleService {
    private final String filePrefix;
    private final NetworkParameters params;
    private volatile BlockChain vChain;
    private volatile SPVBlockStore vStore;
    private volatile Wallet vWallet;
    private volatile PeerGroup vPeerGroup;
    private final DownloadListener listener;

    private final File directory;
    private volatile File vWalletFile;

    private boolean useAutoSave = true;
    private PeerAddress[] peerAddresses;

    public MyWalletAppKit(NetworkParameters params,
                          File directory,
                          String filePrefix,
                          DownloadListener listener) {
        this.params = checkNotNull(params);
        this.directory = checkNotNull(directory);
        this.filePrefix = checkNotNull(filePrefix);
        this.listener = listener;
    }

    /** Will only connect to the given addresses. Cannot be called after startup. */
    public MyWalletAppKit setPeerNodes(PeerAddress... addresses) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.peerAddresses = addresses;
        return this;
    }

    /** Will only connect to localhost. Cannot be called after startup. */
    public MyWalletAppKit connectToLocalHost() {
        try {
            final InetAddress localHost = InetAddress.getLocalHost();
            return setPeerNodes(new PeerAddress(localHost, params.getPort()));
        } catch (UnknownHostException e) {
            // Borked machine with no loopback adapter configured properly.
            throw new RuntimeException(e);
        }
    }

    public MyWalletAppKit setAutoSave(boolean value) {
        checkState(state() == State.NEW, "Cannot call after startup");
        useAutoSave = value;
        return this;
    }

    /**
     * <p>Override this to load all wallet extensions if any are necessary.</p>
     *
     * <p>When this is called, chain(), store(), and peerGroup() will return the created objects, however they are not
     * initialized/started</p>
     */
    protected void addWalletExtensions() throws Exception { }

    /**
     * This method is invoked on a background thread after all objects are initialised, but before the peer group
     * or block chain download is started. You can tweak the objects configuration here.
     */
    protected void onSetupCompleted() { }

    @SuppressLint("NewApi")
	@Override
    protected void startUp() throws Exception {
        // Runs in a separate thread.
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                throw new IOException("Could not create named directory.");
            }
        }
        FileInputStream walletStream = null;
        try {
            File chainFile = new File(directory, filePrefix + ".spvchain");
            vWalletFile = new File(directory, filePrefix + ".wallet");
            boolean shouldReplayWallet = vWalletFile.exists() && !chainFile.exists();
            vStore = new SPVBlockStore(params, chainFile);
            vChain = new BlockChain(params, vStore);
            vPeerGroup = new PeerGroup(params, vChain);
            // Set up peer addresses or discovery first, so if wallet extensions try to broadcast a transaction
            // before we're actually connected the broadcast waits for an appropriate number of connections.
            if (peerAddresses != null) {
                for (PeerAddress addr : peerAddresses) vPeerGroup.addAddress(addr);
                peerAddresses = null;
            } else {
                vPeerGroup.addPeerDiscovery(new DnsDiscovery(params));
            }
            if (vWalletFile.exists()) {
                walletStream = new FileInputStream(vWalletFile);
                vWallet = new Wallet(params);
                addWalletExtensions(); // All extensions must be present before we deserialize
                new WalletProtobufSerializer().readWallet(WalletProtobufSerializer.parseToProto(walletStream), vWallet);
                if (shouldReplayWallet)
                    vWallet.clearTransactions(0);
            } else {
                vWallet = new Wallet(params);
                addWalletExtensions();
            }
            if (useAutoSave) vWallet.autosaveToFile(vWalletFile, 1, TimeUnit.SECONDS, null);
            vChain.addWallet(vWallet);
            vPeerGroup.addWallet(vWallet);
            onSetupCompleted();
            vPeerGroup.startAndWait();

            // Replaced this call with it's contents so we can install
            // our own listener ...
            // vPeerGroup.downloadBlockChain();
            //
            vPeerGroup.startBlockChainDownload(listener);
            try {
                listener.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // Make sure we shut down cleanly.
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override public void run() {
                    try {
                        MyWalletAppKit.this.stopAndWait();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (BlockStoreException e) {
            throw new IOException(e);
        } finally {
            if (walletStream != null) walletStream.close();
        }
    }

    @SuppressLint("NewApi")
	@Override
    protected void shutDown() throws Exception {
        // Runs in a separate thread.
        try {
            vPeerGroup.stopAndWait();
            vWallet.saveToFile(vWalletFile);
            vStore.close();

            vPeerGroup = null;
            vWallet = null;
            vStore = null;
            vChain = null;
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    public NetworkParameters params() {
        return params;
    }

    public BlockChain chain() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vChain;
    }

    public SPVBlockStore store() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vStore;
    }

    public Wallet wallet() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vWallet;
    }

    public PeerGroup peerGroup() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vPeerGroup;
    }

    public File directory() {
        return directory;
    }
}