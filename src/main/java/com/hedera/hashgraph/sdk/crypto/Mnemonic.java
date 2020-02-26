package com.hedera.hashgraph.sdk.crypto;

import com.hedera.hashgraph.sdk.Internal;
import com.hedera.hashgraph.sdk.crypto.ed25519.Ed25519PrivateKey;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * BIP-39 mnemonic phrases compatible with the Android and iOS mobile wallets.
 */
public final class Mnemonic {
    /**
     * The list of words in this mnemonic.
     */
    public final List<CharSequence> words;

    @Nullable
    private String asString;

    private static final SecureRandom secureRandom = new SecureRandom();

    // by storing our word list in a SoftReference, the GC is free to evict it at its discretion
    // but the implementation is meant to wait until free space is needed
    @Nullable
    private static SoftReference<List<String>> wordList;

    /**
     * Construct a mnemonic from a 24-word list.
     *
     * @param words the 24-word list that constitutes a mnemonic phrase.
     */
    public Mnemonic(List<? extends CharSequence> words) {
        this.words = Collections.unmodifiableList(words);
    }

    /**
     * Recover a mnemonic from a string, splitting on spaces.
     */
    public static Mnemonic fromString(String mnemonicString) {
        return new Mnemonic(Arrays.asList(mnemonicString.split(" ")));
    }

    /**
     * @return a new random 24-word mnemonic from the BIP-39 standard English word list.
     */
    public static Mnemonic generate() {
        final byte[] entropy = new byte[32];
        secureRandom.nextBytes(entropy);

        return new Mnemonic(entropyToWords(entropy));
    }

    /**
     * Recover a private key from this mnemonic phrase.
     * <p>
     * This is not compatible with the phrases generated by the Android and iOS wallets;
     * use the no-passphrase version instead.
     *
     * @param passphrase the passphrase used to protect the mnemonic (not used in the
     *                   mobile wallets, use {@link #toPrivateKey()} instead.)
     * @return the recovered key; use {@link Ed25519PrivateKey#derive(int)} to get a key for an
     * account index (0 for default account)
     * @see Ed25519PrivateKey#fromMnemonic(Mnemonic, String)
     */
    public Ed25519PrivateKey toPrivateKey(String passphrase) {
        return Ed25519PrivateKey.fromMnemonic(this, passphrase);
    }

    /**
     * Recover a private key from this mnemonic phrase.
     *
     * @return the recovered key; use {@link Ed25519PrivateKey#derive(int)} to get a key for an
     * account index (0 for default account)
     * @see Ed25519PrivateKey#fromMnemonic(Mnemonic)
     */
    public Ed25519PrivateKey toPrivateKey() {
        return toPrivateKey("");
    }

    public void validate() throws BadMnemonicException {
        if (words.size() != 24) {
            throw new BadMnemonicException("expected 24-word mnemonic, got " + words.size() + " words");
        }

        ArrayList<Integer> unknownIndices = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            if (getWordIndex(words.get(i)) < 0) {
                unknownIndices.add(i);
            }
        }

        if (!unknownIndices.isEmpty()) {
            String unknownWords = String.join(
                ", ",
                unknownIndices.stream().map(words::get)::iterator);

            throw new BadMnemonicException(
                "the following words in the mnemonic were not in the word list: " + unknownWords,
                unknownIndices);
        }

        // test the checksum encoded in the mnemonic
        byte[] entropyAndChecksum = wordsToEntropyAndChecksum();

        // ignores the 33rd byte
        byte expectedChecksum = checksum(entropyAndChecksum);
        byte givenChecksum = entropyAndChecksum[32];

        if (givenChecksum != expectedChecksum) {
            throw new BadMnemonicException(String.format(
                "mnemonic failed checksum, expected %0#4x, got %0#4x",
                expectedChecksum, givenChecksum));
        }
    }

    @Override
    public String toString() {
        if (asString == null) {
            asString = String.join(" ", words);
        }

        return asString;
    }

    @Internal
    public byte[] toSeed(String passphrase) {
        final String salt = "mnemonic" + passphrase;

        // BIP-39 seed generation
        final PKCS5S2ParametersGenerator pbkdf2 = new PKCS5S2ParametersGenerator(new SHA512Digest());
        pbkdf2.init(
            toString().getBytes(StandardCharsets.UTF_8),
            salt.getBytes(StandardCharsets.UTF_8),
            2048);

        final KeyParameter key = (KeyParameter) pbkdf2.generateDerivedParameters(512);
        return key.getKey();
    }

    private byte[] wordsToEntropyAndChecksum() {
        if (words.size() != 24) {
            throw new BadMnemonicException("expected 24-word mnemonic, got " + words.size() + " words");
        }

        ByteBuffer buffer = ByteBuffer.allocate(33);

        // reverse algorithm of `entropyToWords()` below
        int scratch = 0;
        int offset = 0;
        for (CharSequence word : words) {
            int index = getWordIndex(word);

            if (index < 0) {
                // we throw a nicer error in `validate()` before we even get here
                throw new BadMnemonicException("word not in word list: " + word);
            } else if (index > 0x7FF) {
                throw new Error("(BUG) index out of bounds: " + index);
            }

            scratch |= index << offset;
            offset += 11;

            while (offset >= 8) {
                // truncation is what we want here
                buffer.put((byte) scratch);
                scratch >>= 8;
                offset -= 8;
            }
        }

        return buffer.array();
    }

    private static List<String> entropyToWords(byte[] entropy) {
        // we only care to support 24 word mnemonics
        if (entropy.length != 32) {
            throw new IllegalArgumentException("invalid entropy byte length: " + entropy.length);
        }

        // checksum for 256 bits is one byte
        byte[] bytes = Arrays.copyOf(entropy, 33);
        bytes[32] = checksum(entropy);

        List<String> wordList = getWordList();
        ArrayList<String> words = new ArrayList<>(24);

        int scratch = 0;
        int offset = 0;

        for (byte b : bytes) {
            // shift `bytes` into `scratch`, popping off 11-bit indexes when we can
            scratch |= ((int) b) << offset;
            offset += 8;

            if (offset >= 11) {
                // mask off the first 11 bits
                int index = scratch & 0x7FF;
                words.add(wordList.get(index));

                // shift 11 bits out of `scratch`
                scratch >>= 11;
                offset -= 11;
            }
        }

        return words;
    }

    // hash the first 32 bytes of `entropy` and return the first byte of the digest
    private static byte checksum(byte[] entropy) {
        SHA256Digest digest = new SHA256Digest();
        // hash the first
        digest.update(entropy, 0, 32);

        byte[] checksum = new byte[digest.getDigestSize()];
        digest.doFinal(checksum, 0);

        return checksum[0];
    }

    private static int getWordIndex(CharSequence word) {
        return Collections.binarySearch(getWordList(), word, null);
    }

    private static List<String> getWordList() {
        if (wordList == null || wordList.get() == null) {
            synchronized (Mnemonic.class) {
                if (wordList == null || wordList.get() == null) {
                    List<String> words = readWordList();
                    wordList = new SoftReference<>(words);
                    // immediately return the strong reference
                    return words;
                }
            }
        }

        return wordList.get();
    }

    private static List<String> readWordList() {
        InputStream wordStream = Mnemonic.class.getClassLoader().getResourceAsStream("bip39-english.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(wordStream)))) {
            ArrayList<String> words = new ArrayList<>(2048);

            for (String word = reader.readLine(); word != null; word = reader.readLine()) {
                words.add(word);
            }
            return Collections.unmodifiableList(words);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
