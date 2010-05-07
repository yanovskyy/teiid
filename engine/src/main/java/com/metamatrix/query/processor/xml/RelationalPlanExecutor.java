/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package com.metamatrix.query.processor.xml;

import java.util.List;
import java.util.Map;

import org.teiid.logging.LogManager;

import com.metamatrix.api.exception.MetaMatrixComponentException;
import com.metamatrix.api.exception.MetaMatrixProcessingException;
import com.metamatrix.common.buffer.BlockedException;
import com.metamatrix.common.buffer.BufferManager;
import com.metamatrix.common.buffer.IndexedTupleSource;
import com.metamatrix.query.execution.QueryExecPlugin;
import com.metamatrix.query.mapping.xml.ResultSetInfo;
import com.metamatrix.query.processor.BatchIterator;
import com.metamatrix.query.processor.ProcessorDataManager;
import com.metamatrix.query.processor.ProcessorPlan;
import com.metamatrix.query.processor.QueryProcessor;
import com.metamatrix.query.sql.symbol.ElementSymbol;
import com.metamatrix.query.sql.util.VariableContext;
import com.metamatrix.query.util.CommandContext;


/** 
 * This is a helper class which can execute a supplied relational plan and supply
 * resulting query results to the caller.
 * Note: in future we would want to replace this class with submitting the requests directly to
 * process worker queue.
 */
class RelationalPlanExecutor implements PlanExecutor {
    
    QueryProcessor internalProcessor;

    // information about the result set.
    ResultSetInfo resultInfo;
    // buffer store
    BufferManager bufferMgr;
    // flag to denote the end of rows
    boolean endOfRows = false;
    // results after the execution bucket.
    IndexedTupleSource tupleSource;
    // cached current row of results.
    List currentRow;
    int currentRowNumber = 0;
    
    public RelationalPlanExecutor (ResultSetInfo resultInfo, CommandContext context, ProcessorDataManager dataMgr, BufferManager bufferMgr) 
        throws MetaMatrixComponentException{
        
        this.resultInfo = resultInfo;
        this.bufferMgr = bufferMgr;
        
        ProcessorPlan plan = resultInfo.getPlan();
        CommandContext subContext = (CommandContext)context.clone();
        subContext.pushVariableContext(new VariableContext());
        this.internalProcessor = new QueryProcessor(plan, subContext, bufferMgr, dataMgr);
    }
    
    /** 
     * @see com.metamatrix.query.processor.xml.PlanExecutor#getOutputElements()
     */
    public List getOutputElements() throws MetaMatrixComponentException {
        ProcessorPlan plan = resultInfo.getPlan();
        return plan.getOutputElements();
    }      

    /**
     * @throws MetaMatrixProcessingException 
     * @see com.metamatrix.query.processor.xml.PlanExecutor#execute(java.util.Map)
     */
    public void execute(Map referenceValues) throws MetaMatrixComponentException, BlockedException, MetaMatrixProcessingException {        
        if (this.tupleSource == null) {
        	setReferenceValues(referenceValues);
            this.tupleSource = new BatchIterator(internalProcessor);
        }
        //force execution
        this.tupleSource.hasNext();
    }    
    
    void setReferenceValues(Map<ElementSymbol, Object> referencesValues) {
        if (referencesValues == null || referencesValues.isEmpty()) {
        	return;
        }
        for (Map.Entry<ElementSymbol, Object> entry : referencesValues.entrySet()) {
            this.internalProcessor.getContext().getVariableContext().setValue(entry.getKey(), entry.getValue());
		}
    }    
    
    /**
     * Get the next row from the result set
     * @return
     * @throws MetaMatrixComponentException
     */
    public List nextRow() throws MetaMatrixComponentException, MetaMatrixProcessingException {
        // if we not already closed the tuple source; look for more results.
        if (!endOfRows) {

            // get the next row
            this.currentRow = this.tupleSource.nextTuple();     
            this.currentRowNumber++;

            // check if we walked over the row limit
            if (this.currentRow != null && this.resultInfo.getUserRowLimit() > 0 && this.currentRowNumber > this.resultInfo.getUserRowLimit()) {
                if (this.resultInfo.exceptionOnRowlimit()) {
                    throw new MetaMatrixProcessingException(QueryExecPlugin.Util.getString("row_limit_passed", new Object[] { new Integer(this.resultInfo.getUserRowLimit()), this.resultInfo.getResultSetName()})); //$NON-NLS-1$                
                }
                // well, we did not throw a exception, that means we need to limit it to current row
                this.currentRow = null;
            }
        }
        
        if (this.currentRow == null) {
            this.endOfRows = true;
        }
        return this.currentRow;
    }
    
    /**
     * Get the current row. 
     * @return
     */
    public List currentRow() throws MetaMatrixComponentException, MetaMatrixProcessingException {
        // automatically forward to the very first row.
        if (this.currentRow == null && !endOfRows) {
            return nextRow();
        }

        return this.currentRow;
    }
    
    /**
     * Close the executor and release all the resources.
     */
    public void close() throws MetaMatrixComponentException {
		this.internalProcessor.closeProcessing();
        LogManager.logTrace(org.teiid.logging.LogConstants.CTX_XML_PLAN, new Object[]{"closed executor", resultInfo.getResultSetName()}); //$NON-NLS-1$
    }
  
}
