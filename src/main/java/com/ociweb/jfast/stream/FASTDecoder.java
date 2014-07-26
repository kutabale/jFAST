package com.ociweb.jfast.stream;

import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.TemplateCatalogConfig;
import com.ociweb.jfast.primitive.PrimitiveReader;

public abstract class FASTDecoder{
    
    //active state, TODO: C, minimize or remove these.
    
   //does not appear to make big difference. probably not worth it because the write of decimals is now more complex, if not easily solved discard.
    public final static boolean WRITE_CONST = true; //TODO: A, turn off when rest of code supports not sending constants. Must fix unit tests and encoder.
    
    //all constants always skipped.
    //decimals as well??
    
    private final int[] templateStartIdx; //These constants can be remvoed
    private final int[] templateLimitIdx;//These constants can be remvoed
    

    //runtime count of sequence lengths
    public int sequenceCountStackHead = -1;
    public final int[] sequenceCountStack;
    
    //private ring buffers for writing content into
    public final RingBuffers ringBuffers;
    
    //dictionary data
    protected final long[] rLongDictionary; //final array with constant references
    protected final int[] rIntDictionary; //final array with constant references
    protected final LocalHeap byteHeap;
    
    public int activeScriptCursor=-1; //needed by generated code to hold state between calls.
    public int ringBufferIdx= -1; //must hold return value from beginning of fragment to the end.
    public int templateId=-1; //must hold between read (wait for space on queue) and write of templateId
    public int preambleA=0; //must hold between read (wait for space on queue) and write (if it happens)
    public int preambleB=0; //must hold between read (wait for space on queue) and write (if it happens)
            
   
        
    public FASTDecoder(TemplateCatalogConfig catalog) {
        this(catalog.dictionaryFactory(), catalog.getMaxGroupDepth(), computePMapStackInBytes(catalog), 
             catalog.getTemplateStartIdx(), catalog.getTemplateLimitIdx(),
             catalog.maxTemplatePMapSize(), catalog.clientConfig().getPreableBytes(), catalog.ringBuffers());
    }
    
    private static int computePMapStackInBytes(TemplateCatalogConfig catalog) {
        return 2 + ((Math.max(
                catalog.maxTemplatePMapSize(), catalog.maxNonTemplatePMapSize()) + 2) * catalog.getMaxGroupDepth());
    }
    
            
    private FASTDecoder(DictionaryFactory dcr, int maxNestedGroupDepth, int maxPMapCountInBytes,
            int[] templateStartIdx, int[] templateLimitIdx,
            int maxTemplatePMapSize, int preambleDataLength, RingBuffers ringBuffers) {

        this.byteHeap = dcr.byteDictionary();
        
        this.sequenceCountStack = new int[maxNestedGroupDepth];
        this.rIntDictionary = dcr.integerDictionary();
        this.rLongDictionary = dcr.longDictionary();
        
        this.templateStartIdx = templateStartIdx;
        this.templateLimitIdx = templateLimitIdx;
        
        this.ringBuffers = ringBuffers;
        
        assert (rIntDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rIntDictionary.length));
        assert (rLongDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rLongDictionary.length));
    }
    
    
    public void reset(DictionaryFactory dictionaryFactory) {
                
        // clear all previous values to un-set
        dictionaryFactory.reset(rIntDictionary);
        dictionaryFactory.reset(rLongDictionary);
        if (null!=byteHeap) {
            LocalHeap.reset(byteHeap);
        }
        sequenceCountStackHead = -1;
        
        RingBuffers.reset(ringBuffers);        

    }

    public abstract int decode(PrimitiveReader reader);
        
  
    

    public int activeScriptLimit; //TODO: B, remvoe this once limit is removed from iterprister after stack is used for exit flag.
    
    //TODO: B, remove or change to static.
    public int requiredBufferSpace2() {
        
        activeScriptCursor = templateStartIdx[templateId];//set location for the generated code state.
        activeScriptLimit = templateLimitIdx[templateId];

        return (activeScriptLimit - activeScriptCursor) << 2;        
        
    }
    

}
