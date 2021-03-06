package com.rainyalley.architecture.arithmetic.sort;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class DoubleWayReadStoreMergeSortTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DoubleWayReadStoreMergeSortTest.class);

    public void sort() throws Exception {

        Charset charset = Charset.forName("UTF-8");
        Random radom = new Random();

        LOGGER.debug("fill value");
        File file = new File("/var/sort/architecture_user.csv");
        file.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
        for (int i = 0; i < 100000; i++) {
            long id = radom.nextLong();
            bw.write( id + ",中文名字A,中文密码,1");
            bw.newLine();
        }
        bw.flush();
        bw.close();

        LOGGER.debug("findSizeAndMaxLineWidth");
        BufferedReader br = new BufferedReader(new FileReader(file));
        final Pair<Long,Integer> pair = CsvByteDataConverter.findSizeAndMaxLineWidth(br, charset);
        br.close();


        LOGGER.debug("set FileStore");
        DoubleWayReadStore<CsvRow> ies = new DoubleWayReadStore<CsvRow>(new FileStore<>("/var/sort/architecture_user.es", pair.getLeft(), new CsvByteDataConverter(pair.getRight(), charset)), 2000000/pair.getRight());

        try(BufferedReader brb = new BufferedReader(new FileReader(file))){
            int index = 0;
            String line = null;
            while ((line = brb.readLine()) != null){
                CsvRow csvRow = new CsvRow(line);
                ies.set(index, csvRow);
                index++;
            }
            br.close();
            ies.flush();
        }

        try {
            LOGGER.debug("sort");
            DoubleWayReadStoreMergeSort sort = new DoubleWayReadStoreMergeSort();
            sort.sort(ies);

            LOGGER.debug("set ArrayList");
            ArrayList<CsvRow> al = new ArrayList<CsvRow>();
            try(BufferedReader brc = new BufferedReader(new FileReader(file))){
                String line = null;
                while ((line = brc.readLine()) != null) {
                    CsvRow csvRow = new CsvRow(line);
                    al.add(csvRow);
                }
            }

            LOGGER.debug("sort ArrayList");
            Collections.sort(al);

            LOGGER.debug("assert");
            for (int i = 0; i < pair.getLeft(); i++) {
                Assert.assertTrue(ies.getLeftWay(i).compareTo(al.get(i)) == 0);
            }

            LOGGER.debug("complete");
        } catch (Exception e){
            throw new RuntimeException(e);
        } finally {
            file.delete();
            ies.delete();
        }
    }

}