/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.build.finder.core;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.arrayWithSize;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildFinderTest {
    private static List<String> files;

    @BeforeAll
    static void setTarget() throws IOException {
        File target = new File(TestUtils.resolveFileResource("./", "").getParentFile().getParentFile(), "pom.xml");
        assertThat(target.canRead(), is(true));
        files = Collections.singletonList(target.getPath());
    }

    @Test
    void verifyDirectory(@TempDir File folder) throws IOException {
        ChecksumType checksumType = ChecksumType.sha1;
        BuildConfig config = new BuildConfig();

        config.setChecksumTypes(EnumSet.of(checksumType));
        config.setOutputDirectory(folder.getAbsolutePath());

        DistributionAnalyzer da = new DistributionAnalyzer(files, config);
        da.checksumFiles();
        da.outputToFile(checksumType);

        File[] file = folder.listFiles();

        assertThat(file, allOf(is(notNullValue()), is(arrayWithSize(1))));
        assertThat(da.getChecksumFile(checksumType).getCanonicalPath(), is(file[0].getCanonicalPath()));
    }

    @Test
    void verifyloadChecksumsFile(@TempDir File folder) throws IOException {
        ChecksumType checksumType = ChecksumType.md5;
        BuildConfig config = new BuildConfig();

        config.setChecksumTypes(EnumSet.of(checksumType));
        config.setOutputDirectory(folder.getAbsolutePath());

        DistributionAnalyzer da = new DistributionAnalyzer(files, config);
        da.checksumFiles();
        da.outputToFile(checksumType);

        Map<String, Collection<String>> checksums = JSONUtils.loadChecksumsFile(da.getChecksumFile(checksumType));

        assertThat(checksums, is(aMapWithSize(1)));
    }
}
