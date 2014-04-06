//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.field;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.primitive.PrimitiveReader;

public class FieldReaderBytes {

	private static final int INIT_VALUE_MASK = 0x80000000;
	final byte NULL_STOP = (byte)0x80;
	private final PrimitiveReader reader;
	private final ByteHeap heap;
	private final int INSTANCE_MASK;
	
	//TODO: improvement reader/writer bytes/chars should never build this object when it is not in use.
	public FieldReaderBytes(PrimitiveReader reader, ByteHeap byteDictionary) {
		assert(null==byteDictionary || byteDictionary.itemCount()<TokenBuilder.MAX_INSTANCE);
		assert(null==byteDictionary || TokenBuilder.isPowerOfTwo(byteDictionary.itemCount()));
		
		this.INSTANCE_MASK = null==byteDictionary ? 0 :Math.min(TokenBuilder.MAX_INSTANCE, byteDictionary.itemCount()-1);
		
		this.reader = reader;
		this.heap = byteDictionary;
	}

	public int readBytes(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		int length = reader.readIntegerUnsigned();
		reader.readByteData(heap.rawAccess(), 
							heap.allocate(idx, length),
				            length);
		return idx;
	}


	public int readBytesTail(int token, int readFromIdx) {
		
		//return readBytesCopy(token);
		
		int idx = token & INSTANCE_MASK;
				
		int trim = reader.readIntegerUnsigned();
		int length = reader.readIntegerUnsigned(); 
		
		//append to tail	
		int targetOffset = heap.makeSpaceForAppend(idx, trim, length);
		reader.readByteData(heap.rawAccess(), targetOffset, length);
				
		return idx;
	}
	
	public void reset() {
		if (null!=heap) {
			heap.reset();
		}
	}

	
	public int readBytesConstant(int token, int readFromIdx) {
		//always return this required value
		return token & INSTANCE_MASK;
	}

	public int readBytesDelta(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		
		int trim = reader.readIntegerSigned();
		int utfLength = reader.readIntegerUnsigned();
		if (trim>=0) {
			//append to tail
			reader.readByteData(heap.rawAccess(), heap.makeSpaceForAppend(idx, trim, utfLength), utfLength);
		} else {
			//append to head
			reader.readByteData(heap.rawAccess(), heap.makeSpaceForPrepend(idx, -trim, utfLength), utfLength);
		}
		
		return idx;
	}

	public int readBytesCopy(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()!=0) {
			int length = reader.readIntegerUnsigned();
			reader.readByteData(heap.rawAccess(), 
								heap.allocate(idx, length),
					            length);
		}
		return idx;
	}

	public int readBytesDefault(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			//System.err.println("z");
			return idx|INIT_VALUE_MASK;//use constant
		} else {
			//System.err.println("a");
			int length = reader.readIntegerUnsigned();
			if (length>65535 || length<0) {
				throw new FASTException("do you really want ByteArray of size "+length);
			}
			assert(length>=0) : "Unsigned int are never negative";
			reader.readByteData(heap.rawAccess(), 
								heap.allocate(idx, length),
					            length);
						
			return idx;
		}
	}

	public int readBytesOptional(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		int length = reader.readIntegerUnsigned()-1;
		reader.readByteData(heap.rawAccess(), 
							heap.allocate(idx, length),
				            length);
		return idx;
	}

	public int readBytesTailOptional(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		
		int trim = reader.readIntegerUnsigned();
		if (trim==0) {
			heap.setNull(idx);
			return idx;
		} 
		trim--;
		
		int utfLength = reader.readIntegerUnsigned();

		//append to tail	
		reader.readByteData(heap.rawAccess(), heap.makeSpaceForAppend(idx, trim, utfLength), utfLength);
		
		return idx;
	}

	public int readBytesConstantOptional(int token, int readFromIdx) {
		return (reader.popPMapBit()==0 ? (token & INSTANCE_MASK)|INIT_VALUE_MASK : token & INSTANCE_MASK);
	}

	public int readBytesDeltaOptional(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		
		int trim = reader.readIntegerSigned();
		if (0==trim) {
			heap.setNull(idx);
			return idx;
		}
		if (trim>0) {
			trim--;//subtract for optional
		}
		
		int utfLength = reader.readIntegerUnsigned();

		if (trim>=0) {
			//append to tail
			reader.readByteData(heap.rawAccess(), heap.makeSpaceForAppend(idx, trim, utfLength), utfLength);
		} else {
			//append to head
			reader.readByteData(heap.rawAccess(), heap.makeSpaceForPrepend(idx, -trim, utfLength), utfLength);
		}
		
		return idx;
	}

	public int readBytesCopyOptional(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		if (reader.popPMapBit()!=0) {			
			int length = reader.readIntegerUnsigned()-1;
			reader.readByteData(heap.rawAccess(), 
								heap.allocate(idx, length),
					            length);
		}	
		return idx;
	}

	public int readBytesDefaultOptional(int token, int readFromIdx) {
		int idx = token & INSTANCE_MASK;
		
		if (reader.popPMapBit()==0) {
			return idx|INIT_VALUE_MASK;//use constant
		} else {
			
			int length = reader.readIntegerUnsigned()-1;
			if (length>65535 || length<0) {
				throw new FASTException("do you really want ByteArray of size "+length);
			}
			reader.readByteData(heap.rawAccess(), 
								heap.allocate(idx, length),
					            length);
						
			return idx;
		}
	}

	public ByteHeap byteHeap() {
		return heap;
	}

	public void reset(int idx) {
		if (null!=heap) {
			heap.setNull(idx);
		}
	}



}
