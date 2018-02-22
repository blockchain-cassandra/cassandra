/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.blockchain;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import com.google.common.collect.ImmutableList;

import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.exceptions.BlockchainBrokenException;
import org.apache.cassandra.schema.ColumnMetadata;

/***
 * Generate a routine to verify if the HashChain is unbroken
 */
public class VerifyHashIterative extends VerifyHash
{
    //List with key, callable with sting column names
    private static HashMap<ByteBuffer, HashMap<String, ByteBuffer>> table;

    public static boolean verify(String tableName)
    {

        System.out.println("Start validating table " + tableName + " iteratively");
        setTableName(tableName);
        loadMetadata();
        generateTable();

        //Validate, start with head
        String calculatedHash = validateList();
        return calculatedHash.equals(BlockchainHandler.getPredecessorHash());
    }

    private static void generateTable()
    {
        table = new HashMap<>();
        UntypedResultSet rs = FormatHelper.executeQuery("SELECT * FROM " + tableName);
        String columname;

        for (UntypedResultSet.Row row : rs)
        {
            System.out.println("------------------------------------------------");
            row.printFormated();
        }


        //Get the first Key column name
        ImmutableList<ColumnMetadata> columnMetadata = metadata.partitionKeyColumns();
        ListIterator<ColumnMetadata> columnMetadataListIterator = columnMetadata.listIterator();
        String keyName = columnMetadataListIterator.next().name.toString();

        for (UntypedResultSet.Row row : rs)
        {
            List<ColumnSpecification> cs = row.getColumns();

            HashMap<String, ByteBuffer> tmp = new HashMap<>();
            ByteBuffer key = null;
            for (ColumnSpecification column : cs)
            {
                columname = column.name.toString();
                if (columname.contains(keyName))
                {
                    key = row.getBytes(columname);
                }
                else
                {
                    if (row.getBytes(columname) == null)
                    {
                        tmp.put(columname, null);
                    }
                    else
                    {
                        tmp.put(columname, row.getBytes(columname));
                    }
                }
            }
            table.put(key, tmp);
        }

        //for Debugging
        /*
        System.out.println("Print Table");
        for (Map.Entry<ByteBuffer, HashMap<String, ByteBuffer>> entry : table.entrySet())
        {
            ByteBuffer tmpkey = entry.getKey();
            System.out.println("---\nMain Key: " + FormatHelper.convertByteBufferToString(tmpkey) + "\n=>");
            HashMap value = entry.getValue();
            value.forEach((k, v) -> System.out.println("key: " + k + " value: " + FormatHelper.convertByteBufferToString((ByteBuffer) v)));
        }
        */
    }

    private static String validateList()
    {
        List<ByteBuffer> order = new LinkedList<>();
        //Set first key to head of blockchain
        ByteBuffer key = BlockchainHandler.getBlockChainHead();
        assert key != null : "Key can't be empty";
        //Sort List
        do
        {
            System.out.println("Key: " + FormatHelper.convertByteBufferToString(key));
            order.add(0, key);
            if (table.get(key) == null)
            {
                break;
            }
            else
            {
                System.out.println("Key in List: " + FormatHelper.convertByteBufferToString(table.get(key).get("predecessor")));
                key = table.get(key).get("predecessor");
            }
        } while (key != null && !key.equals(BlockchainHandler.getNullBlock()));

        System.out.println("Ordered");
        for (ByteBuffer orderedkey : order)
        {
            System.out.println("Key: " + FormatHelper.convertByteBufferToString(orderedkey));
        }


        ByteBuffer[] valueColumns = new ByteBuffer[metadata.columns().size()];
        ByteBuffer timestamp = null;

        String hash = "";
        String lastHash = "";
        String calculatedHash;

        int cvcounter;

        for (ByteBuffer orderedkey : order)
        {
            if (orderedkey == null) continue;
            if (table.get(orderedkey) == null) continue;
            cvcounter = 0;

            for (ColumnMetadata cm : metadata.columns())
            {
                String cmname = cm.name.toString();
                if (cmname.equals(BlockchainHandler.getBlockchainIDString()))
                {
                    //Do nothing for the key Value
                }
                else if (cmname.equals("timestamp"))
                {
                    timestamp = table.get(orderedkey).get(cmname);
                }
                else if (cmname.equals("hash"))
                {
                    if (table.get(orderedkey).get(cmname) != null)
                    {
                        lastHash = UTF8Type.instance.compose(table.get(orderedkey).get(cmname));
                    }
                    else
                    {
                        lastHash = "";
                    }
                }
                else
                {
                    valueColumns[cvcounter++] = table.get(orderedkey).get(cmname);
                }
            }
            calculatedHash = BlockchainHandler.calculateHash(orderedkey, removeEmptyCells(valueColumns), timestamp, hash);
            //System.out.println(calculatedHash);
            if (!calculatedHash.equals(lastHash))
            {
                throw new BlockchainBrokenException(orderedkey, calculatedHash);
            }
            hash = lastHash;
        }

        return hash;
    }
}
