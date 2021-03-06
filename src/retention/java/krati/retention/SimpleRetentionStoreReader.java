/*
 * Copyright (c) 2010-2011 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package krati.retention;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import krati.retention.clock.Clock;
import krati.store.DataStore;
import krati.util.IndexedIterator;

/**
 * SimpleRetentionStoreReader
 * 
 * @param <K> Key
 * @param <V> Value
 * @version 0.4.2
 * @author jwu
 * 
 * <p>
 * 08/23, 2011 - Created <br/>
 * 11/20, 2011 - Updated for supporting both SimpleRetention and SimpleEventBus <br/>
 * 01/25, 2012 - Fixed bootstrap scan logging info <br/>
 */
public class SimpleRetentionStoreReader<K, V> implements RetentionStoreReader<K, V> {
    private final static Logger _logger = Logger.getLogger(SimpleRetentionStoreReader.class);
    private final String _source;
    private final Retention<K> _retention;
    private final DataStore<K, V> _store;
    
    public SimpleRetentionStoreReader(String source, Retention<K> retention, DataStore<K, V> store) {
        this._source = source;
        this._retention = retention;
        this._store = store;
    }
    
    public final DataStore<K, V> getStore() {
        return _store;
    }
    
    public final Retention<K> getRetention() {
        return _retention;
    }
    
    @Override
    public final String getSource() {
        return _source;
    }
    
    @Override
    public Position getPosition() {
        return _retention.getPosition();
    }
    
    @Override
    public Position getPosition(Clock sinceClock) {
        if(Clock.ZERO != sinceClock) {
            Position pos = _retention.getPosition(sinceClock);
            if(pos != null) {
                return pos;
            }
        }
        
        return new SimplePosition(_retention.getId(), _retention.getOffset(), 0, sinceClock);
    }
    
    @Override
    public V get(K key) throws Exception {
        return key == null ? null : _store.get(key);
    }
    
    @Override
    public Position get(Position pos, Map<K, Event<V>> map) {
        ArrayList<Event<K>> list = new ArrayList<Event<K>>(1000);
        Position nextPos = get(pos, list);
        
        for(Event<K> evt : list) {
            K key = evt.getValue();
            if(key != null) {
                try {
                    V value = _store.get(key);
                    map.put(key, new SimpleEvent<V>(value, evt.getClock()));
                } catch(Exception e) {
                    _logger.warn(e.getMessage());
                }
            }
        }
        
        return nextPos;
    }
    
    @Override
    public Position get(Position pos, List<Event<K>> list) {
        if(pos.getId() != _retention.getId()) {
            if(pos.isIndexed()) {
                throw new InvalidPositionException("Bootstrap reconnection rejected", pos);
            } else {
                Position newPos = getPosition(pos.getClock());
                if(newPos == null) {
                    newPos = new SimplePosition(_retention.getId(), _retention.getOffset(), 0, pos.getClock());
                    _logger.warn("Reset position from " + pos + " to " + newPos);
                }
                pos = newPos;
            }
        }
        
        // Reset position if necessary
        if(pos.getOffset() < _retention.getOrigin()) {
            Position newPos = new SimplePosition(_retention.getId(), _retention.getOffset(), 0, pos.getClock());
            _logger.warn("Reset position from " + pos + " to " + newPos);
            pos = newPos;
        }
        
        // Read from the retention directly
        Position nextPos = _retention.get(pos, list);
        
        // Out of retention and need to start bootstrap
        if(nextPos == null && pos.isIndexed()) {
            int index = pos.getIndex();
            IndexedIterator<K> iter = _store.keyIterator(); 
            
            try {
                iter.reset(index);
            } catch(ArrayIndexOutOfBoundsException e) {
                return new SimplePosition(_retention.getId(), pos.getOffset(), pos.getClock());
            }
            
            int cnt = 0;
            int lastIndex = index;
            while(iter.hasNext()) {
                lastIndex = iter.index();
                K key = iter.next();
                index = iter.index();
                
                list.add(new SimpleEvent<K>(key, pos.getClock()));
                cnt++;
                
                if(cnt >= _retention.getBatchSize()) {
                    if(lastIndex == index) {
                        while(iter.hasNext() && iter.index() == index) {
                            key = iter.next();
                            list.add(new SimpleEvent<K>(key, pos.getClock()));
                            cnt++;
                        }
                        index++;
                    }
                    
                    // Exit loop when enough events are collected
                    break;
                }
            }
            
            if(cnt > 0) {
                _logger.info("Read[" + pos.getIndex() + "," + index + ") " + cnt);
            }
            
            return iter.hasNext() ?
                    new SimplePosition(_retention.getId(), pos.getOffset(), index, pos.getClock()) :
                    new SimplePosition(_retention.getId(), pos.getOffset(), pos.getClock());
        } else {
            return nextPos;
        }
    }
}
