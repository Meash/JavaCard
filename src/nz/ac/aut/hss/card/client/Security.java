/**
 A SecurityService that handles encryption and decryption of APDU
 Note that only the commandProperties have been implemented below
 @see SecureRMIDemoApplet
 */
package nz.ac.aut.hss.card.client;

import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.framework.service.BasicService;
import javacard.framework.service.SecurityService;
import javacard.security.AESKey;
import javacard.security.CryptoException;
import javacard.security.KeyBuilder;
import javacardx.crypto.Cipher;

public class Security extends BasicService implements SecurityService {
	private boolean appProviderAuthenticated, cardIssuerAuthenticated,
			cardHolderAuthenticated;
	private byte sessionProperties; // bits give session secure props
	private byte commandProperties; // bits give command secure props
	private AESKey key;
	private byte[] keyBytes = {103, -125, -92, 79, -126, -49, 48, -84, -85, 113,
			-13, 41, -58, -106, -17, 31}; // 16 bytes for a 128-bit AES cipher
	private byte[] ivBytes = {66, 49, 70, 39, 120, -90, 81, -83, 60, -19, 6, 123,
			53, 91, -80, -89}; // 16 bytes (one block) initialization vector
	private Cipher cipher; // AES cipher in CBC mode with no padding
	private byte[] tempTransientArray; //may be reused so use with care
	private static final byte BLOCK_SIZE = 16; // 16 byte cipher blocks
	private static final byte CLA_SECURITY_BITS_MASK = (byte) 0x0C;
	private static final byte OFFSET_OUT_LA = (byte) 4;
	private static final byte OFFSET_OUT_RDATA = (byte) 5;

	public Security() {
		super();
		resetSecuritySettings();
		// create a key that uses transient memory and so which must
		// be reinitialized whenever applet is deselected
		key = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
		cipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
		// create a transient array of initial length 10 bytes
		tempTransientArray = JCSystem.makeTransientByteArray((short) 10, JCSystem.CLEAR_ON_DESELECT);
	}

	public void setKey(final AESKey key) {
		this.key = key;
		cipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
	}

	// helper method that resets the security settings
	private void resetSecuritySettings() {
		appProviderAuthenticated = false;
		cardIssuerAuthenticated = false;
		cardHolderAuthenticated = false;
		sessionProperties = 0;
		commandProperties = 0;
	}

	// overridden method of BasicService that performs decryption
	// returns true if no more preprocessing should be performed
	// by any other Service that has been added to handles the APDU
	public boolean processDataIn(APDU apdu) {
		if (selectingApplet()) {  // APDU is for selecting applet so clear security settings
			resetSecuritySettings();
			return false; // allow other Services to preprocess if needed
		} else if (!apdu.isSecureMessagingCLA()) {  // APDU CLA byte does not indicate secure messaging
			// clear the appropriate command security properties
			commandProperties &=
					~(SecurityService.PROPERTY_INPUT_CONFIDENTIALITY |
							SecurityService.PROPERTY_OUTPUT_CONFIDENTIALITY);
			return false; // allow other Services to preprocess if needed
		} else {  // set the appropriate command security properties
			commandProperties |=
					(SecurityService.PROPERTY_INPUT_CONFIDENTIALITY |
							SecurityService.PROPERTY_OUTPUT_CONFIDENTIALITY);
			// get the incoming APDU buffer
			byte[] buffer = apdu.getBuffer();
			byte lc = buffer[ISO7816.OFFSET_LC]; // padded length
			byte le = buffer[(short) (ISO7816.OFFSET_LC + lc + 1)];
			// decrypt the data field in the command APDU
			if (lc % BLOCK_SIZE != 0)
				CryptoException.throwIt(CryptoException.ILLEGAL_VALUE);
			if (!key.isInitialized())
				key.setKey(keyBytes, (short) 0); // (re)initialize key
			cipher.init(key, Cipher.MODE_DECRYPT, ivBytes, (short) 0,
					(short) ivBytes.length);
			byte[] deciphertext = getTransientArray(lc);
			cipher.doFinal(buffer, ISO7816.OFFSET_CDATA, lc, deciphertext,
					(short) 0);
			byte numPadding = deciphertext[(short) (lc - 1)];
			byte unpaddedLength = (byte) (lc - numPadding);
			Util.arrayCopyNonAtomic(deciphertext, (short) 0,
					buffer, ISO7816.OFFSET_CDATA, unpaddedLength);
			buffer[ISO7816.OFFSET_LC] = unpaddedLength;
			buffer[(short) (ISO7816.OFFSET_LC + unpaddedLength + 1)] = le;
			// reset the CLA security bits
			buffer[ISO7816.OFFSET_CLA] &= ~CLA_SECURITY_BITS_MASK;
			return true; // don't allow any other preprocessing
		}
	}

	// overridden method of BasicService that performs encryption
	// returns true if no more postprocessing should be performed
	// by any other Service that has been added to handles the APDU
	public boolean processDataOut(APDU apdu) {
		if (selectingApplet())
			return false; //allow other Services to postprocess if needed
		else {  // get outgoing APDU buffer (CLA,INS,SW1,SW2,Le,data field)
			byte[] buffer = apdu.getBuffer();
			// encrypt the data field in response APDU
			if (!key.isInitialized())
				key.setKey(keyBytes, (short) 0); // (re)initialize key
			cipher.init(key, Cipher.MODE_ENCRYPT, ivBytes, (short) 0,
					(short) ivBytes.length);
			byte unpaddedLength = (byte) (buffer[OFFSET_OUT_LA] & 0xFF);
			// pad the buffer segment to have blocks of length BLOCK_SIZE
			// bytes as per PKCS#5 padding scheme where the padding bytes
			// always each give the number of padded bytes
			short numBlocks = (short) ((short) (unpaddedLength + BLOCK_SIZE)
					/ BLOCK_SIZE);
			short paddedLength = (short) (numBlocks * BLOCK_SIZE);
			byte[] padded = getTransientArray(paddedLength);
			Util.arrayCopyNonAtomic(buffer, OFFSET_OUT_RDATA, padded,
					(short) 0, unpaddedLength);
			byte numPadding = (byte) (paddedLength - unpaddedLength);
			for (short i = unpaddedLength; i < paddedLength; i++)
				padded[i] = numPadding;
			if ((short) (OFFSET_OUT_RDATA - 1 + paddedLength) > buffer.length)
				// outgoing buffer can not accommodate the padding
				CryptoException.throwIt(CryptoException.ILLEGAL_VALUE);
			cipher.doFinal(padded, (short) 0, (short) paddedLength, buffer,
					OFFSET_OUT_RDATA);
			buffer[OFFSET_OUT_LA] = (byte) paddedLength;
			return true; // don't allow any other postprocessing
		}
	}

	// returns whether specified principal (APP_PROVIDER, CARD_ISSUER,
	// or CARDHOLDER) is currently authenticated
	public boolean isAuthenticated(short principal) {
		switch (principal) {
			case PRINCIPAL_APP_PROVIDER:
				return appProviderAuthenticated;
			case PRINCIPAL_CARD_ISSUER:
				return cardIssuerAuthenticated;
			case PRINCIPAL_CARDHOLDER:
				return cardHolderAuthenticated;
			default:
				return false; // unknown principal
		}
	}

	// returns whether a channel has been established for this session
	// between the card and host that has the given security properties
	// (INPUT_CONFIDENTIALITY, INPUT_INTEGRITY, OUTPUT_CONFIDENTIALITY,
	// OUTPUT_INTEGRITY)
	public boolean isChannelSecure(byte properties) {
		return (sessionProperties & properties) != 0;
	}

	// returns whether a channel has been established for this command
	// between the card and host that has the given security properties
	// (INPUT_CONFIDENTIALITY, INPUT_INTEGRITY, OUTPUT_CONFIDENTIALITY,
	// OUTPUT_INTEGRITY)
	public boolean isCommandSecure(byte properties) {
		return (commandProperties & properties) != 0;
	}

	// utility method that returns a temporary transient byte array
	// Note this method tries to conserve the amount of smart card
	// RAM that is used (as its VERY scarce) so reuses the same array
	// whenever possible
	private byte[] getTransientArray(short minSize) {
		if (tempTransientArray.length < minSize) {  // try to allocate a larger transient array, note that this
			// might fail if there is not sufficient RAM available
			tempTransientArray = JCSystem.makeTransientByteArray(minSize,
					JCSystem.CLEAR_ON_DESELECT);
		}
		return tempTransientArray;
	}

	public void clearKey() {
		key.clearKey();
	}
}
