/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.examples;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.apache.spark.sql.catalyst.parser.AstBuilder;
import org.apache.spark.sql.catalyst.parser.SqlBaseLexer;
import org.apache.spark.sql.catalyst.parser.SqlBaseParser;
import org.apache.spark.sql.catalyst.parser.UpperCaseCharStream;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;

public class SqlParserExample {
    public static void main(String[] args) {
        String sql = "select id, name from items where date = 20220101";

        SqlBaseLexer lexer = new SqlBaseLexer(new UpperCaseCharStream(CharStreams.fromString(sql)));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        SqlBaseParser parser = new SqlBaseParser(tokenStream);
        parser.SQL_standard_keyword_behavior = true;

        AstBuilder astBuilder = new AstBuilder();
        LogicalPlan logicalPlan;

        try {
            // first, try parsing with potentially faster SLL mode
            parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
            logicalPlan = astBuilder.visitSingleStatement(parser.singleStatement());
        }
        catch (ParseCancellationException e) {
            // if we fail, parse with LL mode
            tokenStream.seek(0); // rewind input stream
            parser.reset();

            // Try Again.
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            logicalPlan = astBuilder.visitSingleStatement(parser.singleStatement());
        }

        System.out.println("Parsed plan:\n" + logicalPlan);
    }
}
