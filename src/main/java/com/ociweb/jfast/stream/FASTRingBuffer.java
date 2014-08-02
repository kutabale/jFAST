package com.ociweb.jfast.stream;

import java.util.concurrent.atomic.AtomicLong;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.FieldReferenceOffsetManager;
import com.ociweb.jfast.util.Profile;

/**
 * Specialized ring buffer for holding decoded values from a FAST stream. Ring
 * buffer has blocks written which correspond to whole messages or sequence
 * items. Within these blocks the consumer is provided random (eg. direct)
 * access capabilities.
 * 
 * 
 * 
 * @author Nathan Tippy
 * 
 * 
 * Storage:
 *  int - 1 slot
 *  long - 2 slots, high then low 
 *  text - 2 slots, index then length  (if index is negative use constant array)
 * 
 */
public final class FASTRingBuffer {

    public static class PaddedLong {
        public long value = 0, padding1, padding2, padding3, padding4, padding5, padding6, padding7;
    }
    
    public final int[] buffer;
    public final int mask;
    public final PaddedLong workingHeadPos = new PaddedLong();
    public final PaddedLong workingTailPos = new PaddedLong();

    public final AtomicLong tailPos = new PaddedAtomicLong(); // producer is allowed to write up to tailPos
    public final AtomicLong headPos = new PaddedAtomicLong(); // consumer is allowed to read up to headPos
    
    
    //grouping fragments together gives a clear advantage but more latency
    //as this gets near half the primary bits size the performance drops off
    //tuning values here can greatly help throughput but should remain <= 1/3 of primary bits
    public final int chunkBits = 4; //TODO: A, move into ringBuffer
    public final int chunkMask = (1<<chunkBits)-1;//TODO: A, move into ringBuffer
    
    public final int maxSize;

    final int maxByteSize;
    public final int byteMask;
    public final byte[] byteBuffer;
    public int addBytePos = 0;
    
    //defined externally and never changes
    final byte[] constByteBuffer;
    final byte[][] bufferLookup;

    long lastRead;

    //TODO: A, use stack of fragment start offsets for each fragment until full message is completed.
    //TODO: B, first offset 0 points to the constants after the ring buffer.
    
    //Need to know when the new template starts
    //each fragment size must be known and looked up
    public FieldReferenceOffsetManager from;
    int[] templateStartIdx;
    

    public FASTRingBuffer(byte primaryBits, byte charBits, DictionaryFactory dcr, FieldReferenceOffsetManager from, int[] templateStartIdx) {
        assert (primaryBits >= 1);       
                
        //single buffer size for every nested set of groups, must be set to support the largest need.
        this.maxSize = 1 << primaryBits;
        this.mask = maxSize - 1;
        
        this.buffer = new int[maxSize];      
        

        //constant data will never change and is populated externally.
        if (null!=dcr) {
            LocalHeap byteHeap = dcr.byteDictionary();
            if (null!=byteHeap) {
                          
                this.constByteBuffer = LocalHeap.rawInitAccess(byteHeap);  
            } else {
                this.constByteBuffer = null;
            }
        } else {
            this.constByteBuffer = null;
        }
                        
        //single text and byte buffers because this is where the variable length data will go.

        this.maxByteSize =  1 << charBits;
        this.byteMask = maxByteSize - 1;
        this.byteBuffer = new byte[maxByteSize];
        this.bufferLookup = new byte[][] {byteBuffer,constByteBuffer};
        
        this.from = from;
        this.templateStartIdx = templateStartIdx;
        
    }

    //TODO: B, must add way of selecting what field to skip writing for the consumer.
    
    /**
     * Empty and restore to original values.
     */
    public void reset() {
        workingHeadPos.value = 0;
        workingTailPos.value = 0;
        tailPos.set(0);
        headPos.set(0); //System.err.println("reset()");
        waiting = false;
        moveNextStop=-1;
        
        /////
        
        messageId = -1;
        isNewMessage = false;
        activeFragmentDataSize = 0;
    }

    // adjust these from the offset of the biginning of the message.

    public int messageId = -1;
    public boolean isNewMessage = false;
    int cursor=-1;
    int[] seqStack = new int[10];//TODO: how deep is this?
    int seqStackHead = -1;
    static final int JUMP_MASK = 0xFFFFF;
    public int activeFragmentDataSize = 0;
 //   private long mnHeadCache=-1;
    private long moveNextStop=-1;
    private boolean waiting = false;
    

    //TODO: B, add method to skip rest of message up to  next message.
    
    public static boolean moveNext(FASTRingBuffer ringBuffer) { //TODO: rename to canMoveNext?

        //check if we are only waiting for the ring buffer to clear
        if (ringBuffer.waiting) {
            ringBuffer.waiting = ringBuffer.moveNextStop>ringBuffer.headPos.longValue();
            return !ringBuffer.waiting;
        }
             
        //finished reading the previous fragment so move the working tail position forward for next fragment to read
        ringBuffer.workingTailPos.value += ringBuffer.activeFragmentDataSize;    
        ringBuffer.activeFragmentDataSize = 0;
        
        
        if (ringBuffer.messageId<0) {      
            //TODO: if there is no room to begin message return false.
           // ringBuffer.tailPos.lazySet(ringBuffer.workingTailPos.value);//hack
            return beginNewMessage(ringBuffer);
        } else {
            return beginFragment(ringBuffer);
        }
        
    }

    private static boolean beginFragment(FASTRingBuffer ringBuffer) {
        ringBuffer.isNewMessage = false;
        int fragStep = ringBuffer.from.fragScriptSize[ringBuffer.cursor]; //script jump 
        ringBuffer.cursor += fragStep;

        ///TODO: B, add optional groups to this implementation
        
        //////////////
        ////Never call these when we jump back for loop
        //////////////
        if (ringBuffer.sequenceLengthDetector(fragStep)) {
            //detecting end of message
            int token = ringBuffer.from.tokens[ringBuffer.cursor];
            if ((ringBuffer.cursor>=ringBuffer.from.tokens.length) || (TokenBuilder.extractType(token)==TypeMask.Group &&
                    0==(token & (OperatorMask.Group_Bit_Seq<< TokenBuilder.SHIFT_OPER)) && //TODO: would be much better with end of MSG bit
                    0!=(token & (OperatorMask.Group_Bit_Close<< TokenBuilder.SHIFT_OPER)))) {
                
                return beginNewMessage(ringBuffer);

            }
        }
            
        //after alignment with front of fragment, may be zero because we need to find the next message?
        ringBuffer.activeFragmentDataSize = ringBuffer.from.fragDataSize[ringBuffer.cursor];//save the size of this new fragment we are about to read
        
        //do not let client read fragment if it is not fully in the ring buffer.
        ringBuffer.moveNextStop = ringBuffer.workingTailPos.value+ringBuffer.activeFragmentDataSize;
        if (ringBuffer.moveNextStop>ringBuffer.headPos.longValue()) {
            ringBuffer.waiting = true;
            return false;
        }
                        
        return true;
    }

    private static boolean beginNewMessage(FASTRingBuffer ringBuffer) {
        
      //Now beginning a new message to release the previous one from the ring buffer
      //This is the only safe place to do this and it must be done before we check for space needed by the next record.
      ringBuffer.tailPos.lazySet(ringBuffer.workingTailPos.value); 
      
      //check if the content id is on the ring buffer.
      if ((int)(ringBuffer.headPos.longValue() - ringBuffer.tailPos.longValue())<2) { //TODO: A, can optmimize too many longValue calls
          ringBuffer.activeFragmentDataSize = 0;
          ringBuffer.messageId=-1;
          return false;
      }
                
        
        //TODO: need to get messageId when its the only message and so not written to the ring buffer.
        //TODO: need to step over the preamble? but how?
        ringBuffer.messageId = FASTRingBufferReader.readInt(ringBuffer,  1); //TODO: how do we know this is one?
            
        if (ringBuffer.messageId<0) {
            System.err.println("Bad data "+ringBuffer.messageId+"  at "+ringBuffer.workingTailPos.value+" tp "+ringBuffer.tailPos.get());
            
        }
        
        //start new message, can not be seq or optional group or end of message.
        ringBuffer.cursor = ringBuffer.from.starts[ringBuffer.messageId];
        ringBuffer.activeFragmentDataSize = ringBuffer.from.fragDataSize[ringBuffer.cursor];//save the size of this new fragment we are about to read
        ringBuffer.isNewMessage = true;
        
        //before letting client continue must confirm that this must data is in the ring buffer.
        ringBuffer.activeFragmentDataSize = ringBuffer.from.fragDataSize[ringBuffer.cursor];//save the size of this new fragment we are about to read
        ringBuffer.moveNextStop = ringBuffer.workingTailPos.value+ringBuffer.activeFragmentDataSize;
        if (ringBuffer.moveNextStop>ringBuffer.headPos.longValue()) {
            ringBuffer.waiting = true;
            return false;
        }      
        
        return true;
    }

    //TODO: B, test is probably does not work with fields following closed sequence.
    
    //only called after moving foward.
    private boolean sequenceLengthDetector(int jumpSize) {
        if(cursor==0) {
            return false;
        }
        int endingToken = from.tokens[cursor-1];
        
        //if last token of last fragment was length then begin new sequence
        int type = TokenBuilder.extractType(endingToken);
        if (TypeMask.GroupLength == type) {
            int seqLength = FASTRingBufferReader.readInt(this, -1); //length is always at the end of the fragment.
                        
            //TODO: off by 2 because 1 for token id and 1 for preamble which are not in the script!!!
            //System.err.println("seq len :"+seqLength+" at "+(this.remPos.value-1)+" "+(this.removeCount.get()-1));
            
            
            if (seqLength == 0) {
//                int jump = (TokenBuilder.MAX_INSTANCE&from.tokens[cursor-jumpSize])+2;
                int fragJump = from.fragScriptSize[cursor+1]; //script jump  //TODO: not sure this is right whenthey are nested?
//                System.err.println(jump+" vs "+fragJump);
         //       System.err.println("******************** jump over seq");
                //do nothing and jump over the sequence
                //there is no data in the ring buffer so do not adjust position
                cursor += (fragJump&JUMP_MASK);
                //done so move to the next item
                
                return true;
            } else {
                if (seqLength<0) {//TODO: turn into assert.
                    throw new FASTException("The previous fragment has already been replaced or modified and it was needed for the length counter");
                }
                
                //push onto stack
                seqStack[++seqStackHead]=seqLength;
                //this is the first run so we are already positioned at the top   
            }
            return false;   
            
        }
                
        
        //if last token of last fragment was seq close then subtract and move back.
        if (TypeMask.Group==type && 
            0 != (endingToken & (OperatorMask.Group_Bit_Seq << TokenBuilder.SHIFT_OPER)) &&
            0 != (endingToken & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER))            
                ) {
            //check top of the stack
            if (--seqStack[seqStackHead]>0) {
                int jump = (TokenBuilder.MAX_INSTANCE&endingToken)+1;
               cursor -= jump;
               return false;
            } else {
                //done, already positioned to continue
                seqStackHead--;                
                return true;
            }
        }                   
        return true;
    }
    
  

    // TODO: C, add map method which can take data from one ring buffer and
    // populate another.

    // TODO: C, Promises/Futures/Listeners as possible better fit to stream
    // processing?
    // TODO: C, look at adding reduce method in addition to filter.


    public static int peek(int[] buf, long pos, int mask) {
        return buf[mask & (int)pos];
    }

    public static long peekLong(int[] buf, long pos, int mask) {
        
        return (((long) buf[mask & (int)pos]) << 32) | (((long) buf[mask & (int)(pos + 1)]) & 0xFFFFFFFFl);

    }

    // TODO: Z, add consumer/Iterator to go from ring buffer to Object stream
    // TODO: Z, Map templates to methods for RMI of void methods(eg. one direction).
    // TODO: Z, add map toIterator method for consuming ring buffer by java8 streams.

    public static void addLocalHeapValue(int heapId, int sourceLen, int rbMask, int[] rbB, PaddedLong rbPos, LocalHeap byteHeap, FASTRingBuffer rbRingBuffer) {
        //int rbMask, int[] rbB  PaddedLong rbPos
        final int p = rbRingBuffer.addBytePos;
        if (sourceLen > 0) {
            rbRingBuffer.addBytePos = LocalHeap.copyToRingBuffer(heapId, rbRingBuffer.byteBuffer, p, rbRingBuffer.byteMask, byteHeap);
        }
        addValue(rbB, rbMask, rbPos, p);
        addValue(rbB, rbMask, rbPos, sourceLen);
    }

    public static void addByteArray(byte[] source, int sourceIdx, int sourceLen, FASTRingBuffer rbRingBuffer) {
        final int p = rbRingBuffer.addBytePos;
        if (sourceLen > 0) {
            rbRingBuffer.addBytePos = LocalHeap.copyToRingBuffer(source, sourceIdx, rbRingBuffer.byteBuffer, p, rbRingBuffer.byteMask, sourceLen);
        }
        addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, p);
        addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, sourceLen);
    }
    
    

    // TODO: D, Callback interface for setting the offsets used by the clients, Generate list of FieldId static offsets for use by static reader based on templateId.
 



    public void removeForward2(long pos) {
        workingTailPos.value = pos;
        tailPos.lazySet(pos);
    }

    //TODO: B: (optimization)finish the field lookup so the constants need not be written to the loop! 
    //TODO: B: build custom add value for long and decimals to avoid second ref out to pos.value
    //TODO: X, back off write if with in cache line distance of tail (full queue case)

   
    //we are only allowed 12% of the time or so for doing this write.
    //this pushes only ~5gbs but if we had 100% it would scale to 45gbs
    //so this is not the real bottleneck and given the compression ratio of the test data
    //we can push 1gbs more of compressed data for each 10% of cpu freed up.
    public static void addValue(int[] buffer, int rbMask, PaddedLong headCache, int value) {
        buffer[rbMask & (int)headCache.value++] = value;
    } 
    
    //long p = headCache.value; //TODO: code gen may want to replace this
    // buffer[rbMask & (int)p] = value; //TODO: code gen replace rbMask with constant may help remove check
    //  headCache.value = p+1;
    
    
    public static void addValue(int[] buffer, int rbMask, PaddedLong headCache, int value1, int value2) {
        
        long p = headCache.value; 
        buffer[rbMask & (int)p] = value1; //TODO: code gen replace rbMask with constant may help remove check
        buffer[rbMask & (int)(p+1)] = value2; //TODO: code gen replace rbMask with constant may help remove check
        headCache.value = p+2;
        
    } 
    
    
    // fragment is ready for consumption
    public static final void unBlockFragment(AtomicLong head, PaddedLong headCache) {
     
        assert(headCache.value>head.get()) : "Can not set the cache smaller than head";        
        head.lazySet(headCache.value);
    }
    
    //TODO: X, Will want to add local cache of atomic in unBlock in order to not lazy set twice because it is called for every close.
    //Called once for every group close, even when nested
    //TODO: B, write padding message if this unblock is the only fragment in the queue.
    
    
    public static void dump(FASTRingBuffer rb) {
                       
        // move the removePosition up to the addPosition
        // new Exception("WARNING THIS IS NO LONGER COMPATIBLE WITH PUMP CALLS").printStackTrace();
        rb.tailPos.lazySet(rb.workingTailPos.value = rb.workingHeadPos.value);
    }

    // WARNING: consumer of these may need to loop around end of buffer !!
    // these are needed for fast direct READ FROM here

    public static int readRingByteLen(int fieldPos, FASTRingBuffer rb) {
        return rb.buffer[rb.mask & (int)(rb.workingTailPos.value + fieldPos + 1)];// second int is always the length
    }
    
    public static int readRingBytePosition(int rawPos) {
        return rawPos&0x7FFFFFFF;//may be negative when it is a constant but lower bits are always position
    }    

    public static byte[] readRingByteBuffers(int rawPos, FASTRingBuffer rbRingBuffer) {
        return rbRingBuffer.bufferLookup[1&(rawPos>>31)];
    }

    public static int readRingByteRawPos(int fieldPos, FASTRingBuffer rbRingBuffer) {
        return rbRingBuffer.buffer[(int)(rbRingBuffer.mask & (rbRingBuffer.workingTailPos.value + fieldPos))];
    }
    

    public int readRingByteMask() {
        return byteMask;
    }

    public final int availableCapacity() {
        return maxSize - (int)(headPos.longValue() - tailPos.longValue());
    }

    
    public static int contentRemaining(FASTRingBuffer rb) {
        return (int)(rb.headPos.longValue() - rb.tailPos.longValue()); //must not go past add count because it is not release yet.
    }

    public int fragmentSteps() {
        return from.fragScriptSize[cursor];
    }

    public static long releaseRecords(final int granularity, AtomicLong hp, PaddedLong wrkHdPos) {
        hp.lazySet(wrkHdPos.value); //we are only looking for space for 1 but wrote n!! records!
        return wrkHdPos.value + granularity;
    }

    public static long spinBlock(AtomicLong atomicLong, long lastCheckedValue, long targetValue) {
        while ( lastCheckedValue < targetValue) {  
            //NOTE: when used with more threads/jobs than cores may want to Thread.yield() here.
            lastCheckedValue = atomicLong.longValue();
        }
        return lastCheckedValue;
    }



}
