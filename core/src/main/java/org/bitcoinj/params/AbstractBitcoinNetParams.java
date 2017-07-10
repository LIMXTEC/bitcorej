/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.params;

import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import org.bitcoinj.core.BitcoinSerializer;

/**
 * Parameters for Bitcoin-like networks.
 */
public abstract class AbstractBitcoinNetParams extends NetworkParameters {

    /**
     * Scheme part for Bitcoin URIs.
     */
    public static final String BITCOIN_SCHEME = "bitcore";
    public static final int REWARD_HALVING_INTERVAL = 210000;

    private static final Logger log = LoggerFactory.getLogger(AbstractBitcoinNetParams.class);

    private static final BigInteger MASK256BIT = new BigInteger("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);

    public AbstractBitcoinNetParams() {
        super();
    }

    /**
     * Checks if we are at a difficulty transition point.
     * @param height The height of the previous stored block
     * @return If this is a difficulty transition point
     */
    public final boolean isDifficultyTransitionPoint(final int height) {
        return ((height + 1) % this.getInterval()) == 0;
    }

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
    	final BlockStore blockStore) throws VerificationException, BlockStoreException {
        if ((storedPrev.getHeight()+1) <= 10000) {
            DUAL_KGW3(storedPrev, nextBlock, blockStore);
        } else {
            final Block prev = storedPrev.getHeader();

            // Is this supposed to be a difficulty transition point?
            if (!isDifficultyTransitionPoint(storedPrev.getHeight())) {

                // No ... so check the difficulty didn't actually change.
                if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                    throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                            ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                            Long.toHexString(prev.getDifficultyTarget()));
                return;
            }

            // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
            // two weeks after the initial block chain download.
            Sha256Hash hash = prev.getHash();
            StoredBlock cursor = null;
            final int interval = this.getInterval()+1;
            for (int i = 0; i < interval; i++) {
                cursor = blockStore.get(hash);
                if (cursor == null) {
                    // This should never happen. If it does, it means we are following an incorrect or busted chain.
                    throw new VerificationException(
                            "Difficulty transition point but we did not find a way back to the last transition point. Not found: " + hash);
                }
                hash = cursor.getHeader().getPrevBlockHash();
            }

            Block blockIntervalAgo = cursor.getHeader();
            int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
            // Limit the adjustment step.
            final int targetTimespan = this.getTargetTimespan();
            if (timespan < targetTimespan / 4)
                timespan = targetTimespan / 4;
            if (timespan > targetTimespan * 4)
                timespan = targetTimespan * 4;

            BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
            newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
            newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

            if (newTarget.compareTo(this.getMaxTarget()) > 0) {
                log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
                newTarget = this.getMaxTarget();
            }

            verifyDifficulty(newTarget, storedPrev, nextBlock);
        }
    }

    private void DUAL_KGW3(StoredBlock storedPrev, Block nextBlock, final BlockStore blockStore)
      throws BlockStoreException, VerificationException {
        final long Blocktime = 96 * 6; // = 9.6 * 60; // 9.6 = 10 min (Value = Value*0.96)
        long TimeDaySeconds = 60 * 60 * 24;
        long PastSecondsMin = TimeDaySeconds / 40;
        long PastSecondsMax = TimeDaySeconds * 7;
        long PastBlocksMin = PastSecondsMin / Blocktime;
        long PastBlocksMax = PastSecondsMax / Blocktime;

        // current difficulty formula, ERC3 - DUAL_KGW3, written by Christian Knoepke - apfelbaum@email.de
        // BitSend and Eropecoin Developer
        StoredBlock BlockLastSolved = storedPrev;
        StoredBlock BlockReading = storedPrev;

        long PastBlocksMass = 0;
        long PastRateActualSeconds = 0;
        long PastRateTargetSeconds = 0;
        double PastRateAdjustmentRatio = 1f;
        BigInteger PastDifficultyAverage = BigInteger.valueOf(0);
        BigInteger PastDifficultyAveragePrev = BigInteger.valueOf(0);;
        double EventHorizonDeviation;
        double EventHorizonDeviationFast;
        double EventHorizonDeviationSlow;

        BigInteger bnPowLimit = this.getMaxTarget();

        //DUAL_KGW3 SETUP
        if (BlockLastSolved == null || BlockLastSolved.getHeight() == 0 || (long)BlockLastSolved.getHeight() < PastBlocksMin) {
            verifyDifficulty(this.getMaxTarget(), storedPrev, nextBlock);
        } else {
            for (int i = 1; BlockReading != null && BlockReading.getHeight() > 0; i++) {
                if (PastBlocksMax > 0 && i > PastBlocksMax) { break; }
                PastBlocksMass++;

                PastDifficultyAverage = BlockReading.getHeader().getDifficultyTargetAsInteger();
                if (i > 1) {
                    if(PastDifficultyAverage.compareTo(PastDifficultyAveragePrev) >= 0)
                        PastDifficultyAverage = ((PastDifficultyAverage.subtract(PastDifficultyAveragePrev)).divide(BigInteger.valueOf(i)).add(PastDifficultyAveragePrev));
                    else
                        PastDifficultyAverage = PastDifficultyAveragePrev.subtract((PastDifficultyAveragePrev.subtract(PastDifficultyAverage)).divide(BigInteger.valueOf(i)));
                }
                PastDifficultyAveragePrev = PastDifficultyAverage;

                PastRateActualSeconds = BlockLastSolved.getHeader().getTimeSeconds() - BlockReading.getHeader().getTimeSeconds();
                PastRateTargetSeconds = Blocktime * PastBlocksMass;
                PastRateAdjustmentRatio = 1.0f;

                if (PastRateActualSeconds < 0)
                    PastRateActualSeconds = 0;

                if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0)
                    PastRateAdjustmentRatio = (double)PastRateTargetSeconds / PastRateActualSeconds;

                EventHorizonDeviation = 1.0 + (0.7084 * java.lang.Math.pow((Double.valueOf(PastBlocksMass)/Double.valueOf(72)), -1.228));
                EventHorizonDeviationFast = EventHorizonDeviation;
                EventHorizonDeviationSlow = 1.0 / EventHorizonDeviation;

                if (PastBlocksMass >= PastBlocksMin)
                    if ((PastRateAdjustmentRatio <= EventHorizonDeviationSlow) || (PastRateAdjustmentRatio >= EventHorizonDeviationFast))
                        break;

                StoredBlock BlockReadingPrev = BlockReading.getPrev(blockStore);
                if (BlockReadingPrev == null) {
                    // Since we are using the checkpoint system, there may not be enough blocks to do this diff adjust, so skip until we do
                    // break;
                    return;
                  }

                BlockReading = BlockReadingPrev;
            }

            //KGW Original
            BigInteger kgw_dual1 = PastDifficultyAverage;
            BigInteger kgw_dual2 = storedPrev.getHeader().getDifficultyTargetAsInteger();
            if (PastRateActualSeconds != 0 && PastRateTargetSeconds != 0) {
                kgw_dual1 = kgw_dual1.multiply(BigInteger.valueOf(PastRateActualSeconds));
                kgw_dual1 = kgw_dual1.and(MASK256BIT); // overflow is here, we need cut it back to 256 bit
                kgw_dual1 = kgw_dual1.divide(BigInteger.valueOf(PastRateTargetSeconds));
                kgw_dual1 = kgw_dual1.and(MASK256BIT);
            }

            StoredBlock BlockPrev = storedPrev.getPrev(blockStore);
            long nActualTime1 = storedPrev.getHeader().getTimeSeconds() - BlockPrev.getHeader().getTimeSeconds();

            // hack caused bug in c implementation
            if (nActualTime1 < 0)
                nActualTime1 = Blocktime;

            long nActualTimespanshort = nActualTime1;

            if (nActualTime1 < Blocktime / 3)
                nActualTime1 = Blocktime / 3;

            if (nActualTime1 > Blocktime * 3)
                nActualTime1 = Blocktime * 3;

            kgw_dual2 = kgw_dual2.multiply(BigInteger.valueOf(nActualTime1));
            kgw_dual2 = kgw_dual2.and(MASK256BIT);
            kgw_dual2 = kgw_dual2.divide(BigInteger.valueOf(Blocktime));
            kgw_dual2 = kgw_dual2.and(MASK256BIT);

            //Fusion from Retarget and Classic KGW3 (BitSend=)
            BigInteger newDifficulty = (kgw_dual2.add(kgw_dual1)).divide(BigInteger.valueOf(2));

            // DUAL KGW3 increased rapidly the Diff if Blocktime to last block under Blocktime/6 sec.
            if( nActualTimespanshort < Blocktime/6 ) {
                newDifficulty = newDifficulty.multiply(BigInteger.valueOf(85));
                newDifficulty = newDifficulty.divide(BigInteger.valueOf(100));
            }

            //BitBreak BitSend
            // Reduce difficulty if current block generation time has already exceeded maximum time limit.
            long nLongTimeLimit = 12 * 60 * 60;
            if ((nextBlock.getTimeSeconds() - storedPrev.getHeader().getTimeSeconds()) > nLongTimeLimit)
                newDifficulty = bnPowLimit;

            if (newDifficulty.compareTo(bnPowLimit) > 0)
                newDifficulty = bnPowLimit;

            verifyDifficulty(newDifficulty, storedPrev, nextBlock);
        }
    }

    private void verifyDifficulty(BigInteger newTarget, StoredBlock storedPrev, Block nextBlock) {
        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

        if (newTargetCompact != receivedTargetCompact)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
    }

    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return version.getBitcoinProtocolVersion();
    }

    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) {
        return new BitcoinSerializer(this, parseRetain);
    }

    @Override
    public String getUriScheme() {
        return BITCOIN_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }
}
