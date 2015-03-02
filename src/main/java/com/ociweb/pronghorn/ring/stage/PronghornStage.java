package com.ociweb.pronghorn.ring.stage;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.GraphManager;
import com.ociweb.pronghorn.ring.RingBuffer;


public abstract class PronghornStage {
	
	private static final Logger log = LoggerFactory.getLogger(PronghornStage.class);
	protected static final RingBuffer[] NONE = new RingBuffer[0];
	
	//What if we only have 1 because this is the first or last stage?

	public final int stageId;	
	private static AtomicInteger stageCounter = new AtomicInteger();
	private GraphManager graphManager;
	
	
	//in the constructor us a zero length array if there are no values.
	protected PronghornStage(GraphManager graphManager, RingBuffer[] inputs, RingBuffer[] outputs) {
		this.stageId = stageCounter.getAndIncrement();	
		this.graphManager = graphManager;
		GraphManager.register(graphManager, this, inputs, outputs);
	}
	
	protected PronghornStage(GraphManager graphManager, RingBuffer input, RingBuffer[] outputs) {
		this.stageId = stageCounter.getAndIncrement();	
		this.graphManager = graphManager;
		GraphManager.register(graphManager, this, input, outputs);
	}
    
	protected PronghornStage(GraphManager graphManager, RingBuffer[] inputs, RingBuffer output) {
		this.stageId = stageCounter.getAndIncrement();	
		this.graphManager = graphManager;
		GraphManager.register(graphManager, this, inputs, output);
	}
	
	protected PronghornStage(GraphManager graphManager, RingBuffer input, RingBuffer output) {
		this.stageId = stageCounter.getAndIncrement();	
		this.graphManager = graphManager;
		GraphManager.register(graphManager, this, input, output);
	}
	
	public static int totalStages() {
		return stageCounter.get();
	}
	
	public void startup() {
		//override to connect to databases etc.
	}

	public String toString() {
		return getClass().getSimpleName()+"["+String.valueOf(stageId)+"]";
	}
	
	public void shutdown() {
		GraphManager.terminate(graphManager, this);
	}
	
	
	/**
	 * Process all the work that is immediately available.
	 * Should periodically return, if it is away too long this can be controlled by making the output ring smaller.
	 * 
	 */
    public abstract void run();
	
	
}