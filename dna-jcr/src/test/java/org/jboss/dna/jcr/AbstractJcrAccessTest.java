/*
 * JBoss DNA (http://www.jboss.org/dna)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * JBoss DNA is free software. Unless otherwise indicated, all code in JBoss DNA
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * JBoss DNA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.dna.jcr;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import java.io.PrintStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import org.jboss.dna.common.statistic.Stopwatch;
import org.jboss.dna.graph.ExecutionContext;
import org.jboss.dna.graph.MockSecurityContext;
import org.jboss.dna.graph.SecurityContext;
import org.jboss.dna.graph.connector.RepositoryConnection;
import org.jboss.dna.graph.connector.RepositoryConnectionFactory;
import org.jboss.dna.graph.connector.RepositorySourceException;
import org.jboss.dna.graph.connector.inmemory.InMemoryRepositorySource;
import org.jboss.dna.graph.observe.MockObservable;
import org.jboss.dna.graph.property.PathFactory;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

/**
 * Support class for performance testing of various operations over subtrees of the content graph
 */

public abstract class AbstractJcrAccessTest {

    private InMemoryRepositorySource source;
    private JcrSession session;
    private JcrRepository repository;

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);

        String workspaceName = "workspace1";

        // Set up the source ...
        source = new InMemoryRepositorySource();
        source.setName(workspaceName);
        source.setDefaultWorkspaceName(workspaceName);

        // Set up the execution context ...
        ExecutionContext context = new ExecutionContext();
        // Register the test namespace
        context.getNamespaceRegistry().register(TestLexicon.Namespace.PREFIX, TestLexicon.Namespace.URI);

        // Stub out the connection factory ...
        RepositoryConnectionFactory connectionFactory = new RepositoryConnectionFactory() {
            /**
             * {@inheritDoc}
             * 
             * @see org.jboss.dna.graph.connector.RepositoryConnectionFactory#createConnection(java.lang.String)
             */
            @SuppressWarnings( "synthetic-access" )
            public RepositoryConnection createConnection( String sourceName ) throws RepositorySourceException {
                return source.getConnection();
            }
        };

        repository = new JcrRepository(context, connectionFactory, "unused", new MockObservable(), null, null);

        SecurityContext mockSecurityContext = new MockSecurityContext("testuser",
                                                                      Collections.singleton(JcrSession.DNA_WRITE_PERMISSION));
        session = (JcrSession)repository.login(new SecurityContextCredentials(mockSecurityContext));
    }

    @After
    public void after() throws Exception {
        if (session != null && session.isLive()) {
            session.logout();
        }
    }

    protected JcrSession session() {
        return this.session;
    }

    private String getRandomString( int length ) {
        StringBuffer buff = new StringBuffer(length);

        for (int i = 0; i < length; i++) {
            buff.append((char)((Math.random() * 26) + 'a'));
        }

        return buff.toString();
    }

    private int createChildren( Node parent,
                                int numProperties,
                                int width,
                                int depth ) throws Exception {
        if (depth < 1) {
            return 0;

        }

        int count = width;

        for (int i = 0; i < width; i++) {
            Node newNode = parent.addNode(getRandomString(9), "nt:unstructured");

            for (int j = 0; j < numProperties; j++) {
                newNode.setProperty(getRandomString(8), getRandomString(16));
            }

            count += createChildren(newNode, numProperties, width, depth - 1);
        }
        return count;
    }

    protected int createSubgraph( JcrSession session,
                                  String initialPath,
                                  int depth,
                                  int numberOfChildrenPerNode,
                                  int numberOfPropertiesPerNode,
                                  boolean oneBatch,
                                  Stopwatch stopwatch,
                                  PrintStream output,
                                  String description ) throws Exception {
        // Calculate the number of nodes that we'll created, but subtract 1 since it doesn't create the root
        long totalNumber = calculateTotalNumberOfNodesInTree(numberOfChildrenPerNode, depth, false);
        if (initialPath == null) initialPath = "";
        if (description == null) {
            description = "" + numberOfChildrenPerNode + "x" + depth + " tree with " + numberOfPropertiesPerNode
                          + " properties per node";
        }

        if (output != null) output.println(description + " (" + totalNumber + " nodes):");
        long totalNumberCreated = 0;

        PathFactory pathFactory = session.getExecutionContext().getValueFactories().getPathFactory();
        Node parentNode = session.getNode(pathFactory.create(initialPath));

        if (stopwatch != null) stopwatch.start();

        totalNumberCreated += createChildren(parentNode, numberOfPropertiesPerNode, numberOfChildrenPerNode, depth);

        assertThat(totalNumberCreated, is(totalNumber));

        session.save();

        if (stopwatch != null) {
            stopwatch.stop();
            if (output != null) {
                output.println("    " + getTotalAndAverageDuration(stopwatch, totalNumberCreated));
            }
        }
        return (int)totalNumberCreated;

    }

    protected int traverseSubgraph( JcrSession session,
                                    String initialPath,
                                    int depth,
                                    int numberOfChildrenPerNode,
                                    int numberOfPropertiesPerNode,
                                    boolean oneBatch,
                                    Stopwatch stopwatch,
                                    PrintStream output,
                                    String description ) throws Exception {
        // Calculate the number of nodes that we'll created, but subtract 1 since it doesn't create the root
        long totalNumber = calculateTotalNumberOfNodesInTree(numberOfChildrenPerNode, depth, false);
        if (initialPath == null) initialPath = "";
        if (description == null) {
            description = "" + numberOfChildrenPerNode + "x" + depth + " tree with " + numberOfPropertiesPerNode
                          + " properties per node";
        }

        if (output != null) output.println(description + " (" + totalNumber + " nodes):");
        long totalNumberTraversed = 0;

        PathFactory pathFactory = session.getExecutionContext().getValueFactories().getPathFactory();
        Node parentNode = session.getNode(pathFactory.create(initialPath));

        if (stopwatch != null) stopwatch.start();

        totalNumberTraversed += traverseChildren(parentNode);

        assertThat(totalNumberTraversed, is(totalNumber));

        session.save();

        if (stopwatch != null) {
            stopwatch.stop();
            if (output != null) {
                output.println("    " + getTotalAndAverageDuration(stopwatch, totalNumberTraversed));
            }
        }
        return (int)totalNumberTraversed;

    }

    protected int traverseChildren( Node parentNode ) throws Exception {

        int childCount = 0;
        NodeIterator children = parentNode.getNodes();

        while (children.hasNext()) {
            childCount++;

            childCount += traverseChildren(children.nextNode());
        }

        return childCount;
    }

    protected String getTotalAndAverageDuration( Stopwatch stopwatch,
                                                 long numNodes ) {
        long totalDurationInMilliseconds = TimeUnit.NANOSECONDS.toMillis(stopwatch.getTotalDuration().longValue());
        if (numNodes == 0) numNodes = 1;
        long avgDuration = totalDurationInMilliseconds / numNodes;
        String units = " millisecond(s)";
        if (avgDuration < 1L) {
            long totalDurationInMicroseconds = TimeUnit.NANOSECONDS.toMicros(stopwatch.getTotalDuration().longValue());
            avgDuration = totalDurationInMicroseconds / numNodes;
            units = " microsecond(s)";
        }
        return "total = " + stopwatch.getTotalDuration() + "; avg = " + avgDuration + units;
    }

    protected int calculateTotalNumberOfNodesInTree( int numberOfChildrenPerNode,
                                                     int depth,
                                                     boolean countRoot ) {
        assert depth > 0;
        assert numberOfChildrenPerNode > 0;
        int totalNumber = 0;
        for (int i = 0; i <= depth; ++i) {
            totalNumber += (int)Math.pow(numberOfChildrenPerNode, i);
        }
        return countRoot ? totalNumber : totalNumber - 1;
    }

}
