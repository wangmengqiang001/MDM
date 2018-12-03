package eu.supersede.mdm.storage.model.omq;

    import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import eu.supersede.mdm.storage.model.Namespaces;
import eu.supersede.mdm.storage.model.metamodel.GlobalGraph;
import eu.supersede.mdm.storage.model.metamodel.SourceGraph;
import eu.supersede.mdm.storage.model.omq.relational_operators.AggregatedAttribute;
import eu.supersede.mdm.storage.model.omq.relational_operators.AggregationFunctions;
import eu.supersede.mdm.storage.model.omq.relational_operators.EquiJoin;
import eu.supersede.mdm.storage.model.omq.relational_operators.Wrapper;
import eu.supersede.mdm.storage.util.KeyedTuple2;
    import eu.supersede.mdm.storage.util.RDFUtil;
    import eu.supersede.mdm.storage.util.Tuple2;
import eu.supersede.mdm.storage.util.Tuple3;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.BasicPattern;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.SimpleGraph;
import scala.util.parsing.combinator.testing.Str;

import java.util.*;

@SuppressWarnings("Duplicates")
public class QueryRewriting_SIGMOD {

    private static void addTriple(BasicPattern pattern, String s, String p, String o) {
        pattern.add(new Triple(new ResourceImpl(s).asNode(), new PropertyImpl(p).asNode(), new ResourceImpl(o).asNode()));
    }
    //Used for adding triples in-memory
    private static void addTriple(Model model, String s, String p, String o) {
        model.add(new ResourceImpl(s), new PropertyImpl(p), new ResourceImpl(o));
    }


    private static OntModel ontologyFromPattern(BasicPattern PHI_p) {
        OntModel o = ModelFactory.createOntologyModel();
        PHI_p.getList().forEach(t ->
                addTriple(o, t.getSubject().getURI(), t.getPredicate().getURI(), t.getObject().getURI())
        );
        return o;
    }

    private static boolean covering(Set<Wrapper> W, BasicPattern PHI_p, Dataset T) {
        BasicPattern coveredPattern = new BasicPattern();
        W.forEach(w -> {
            RDFUtil.runAQuery("SELECT ?s ?p ?o WHERE { GRAPH <" + w.getWrapper() + "> { ?s ?p ?o } }", T).forEachRemaining(res -> {
                coveredPattern.add(new Triple(new ResourceImpl(res.get("s").toString()).asNode(),
                        new PropertyImpl(res.get("p").toString()).asNode(), new ResourceImpl(res.get("o").toString()).asNode()));
            });
        });
        return coveredPattern.getList().containsAll(PHI_p.getList());
    }

    private static boolean minimal(Set<Wrapper> W, BasicPattern PHI_p, Dataset T) {
        for (Wrapper w : W) {
            if (covering(Sets.difference(W,Sets.newHashSet(w)),PHI_p,T)) return false;
        }
        return true;
    }


    private static Set<ConjunctiveQuery> combineCQs(ConjunctiveQuery Ql, ConjunctiveQuery Qr, String Cl, String Cr, Dataset T) {
        Set<ConjunctiveQuery> res = Sets.newHashSet();

        Map<String, Tuple2<Set<Wrapper>, Set<Wrapper>>> IDs_and_their_wrappers = Maps.newHashMap();
        for (Wrapper w : Ql.getWrappers()) {
            //Union should be OK, otherwise we would have not have entered the if that says that both collections of wrappers are disjoint
            ResultSet rs = RDFUtil.runAQuery("SELECT DISTINCT ?f WHERE { GRAPH <" + w.getWrapper() + "> {" +
                    "{ " +
                    "?f <" + Namespaces.rdfs.val() + "subClassOf> <" + Namespaces.sc.val() + "identifier> . " +
                    "<" + Cl + "> <" + GlobalGraph.HAS_FEATURE.val() + "> ?f } " +
                    "UNION { " +
                    "?f <" + Namespaces.rdfs.val() + "subClassOf> <" + Namespaces.sc.val() + "identifier> . " +
                    "<" + Cr + "> <" + GlobalGraph.HAS_FEATURE.val() + "> ?f } " +
                    "} }", T);
            while (rs.hasNext()) {
                String ID = rs.next().get("f").toString();
                if (!IDs_and_their_wrappers.containsKey(ID))
                    IDs_and_their_wrappers.put(ID, new Tuple2<>(Sets.newHashSet(), Sets.newHashSet()));
                Set<Wrapper> wrappersForID = IDs_and_their_wrappers.get(ID)._1;
                wrappersForID.add(w);
                IDs_and_their_wrappers.put(ID, new Tuple2<>(wrappersForID, IDs_and_their_wrappers.get(ID)._2));
            }
        }
        for (Wrapper w : Qr.getWrappers()) {
            ResultSet rs = RDFUtil.runAQuery("SELECT ?f WHERE { GRAPH <" + w.getWrapper() + "> {" +
                    "{ " +
                    "?f <" + Namespaces.rdfs.val() + "subClassOf> <" + Namespaces.sc.val() + "identifier> . " +
                    "<" + Cl + "> <" + GlobalGraph.HAS_FEATURE.val() + "> ?f } " +
                    "UNION { " +
                    "?f <" + Namespaces.rdfs.val() + "subClassOf> <" + Namespaces.sc.val() + "identifier> . " +
                    "<" + Cr + "> <" + GlobalGraph.HAS_FEATURE.val() + "> ?f } " +
                    "} }", T);
            while (rs.hasNext()) {
                String ID = rs.next().get("f").toString();
                if (!IDs_and_their_wrappers.containsKey(ID))
                    IDs_and_their_wrappers.put(ID, new Tuple2<>(Sets.newHashSet(), Sets.newHashSet()));
                Set<Wrapper> wrappersForID = IDs_and_their_wrappers.get(ID)._2;
                wrappersForID.add(w);
                IDs_and_their_wrappers.put(ID, new Tuple2<>(IDs_and_their_wrappers.get(ID)._1, wrappersForID));
            }
        }
        IDs_and_their_wrappers.entrySet().forEach(entry -> {
            String feature = entry.getKey();
            //Different ways of doing the join
            Sets.cartesianProduct(entry.getValue()._1, entry.getValue()._2).forEach(wrapper_combination -> {

                Wrapper wrapperA = wrapper_combination.get(0);
                Wrapper wrapperB = wrapper_combination.get(1);

                String attA = RDFUtil.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                        "?a <" + Namespaces.owl.val() + "sameAs> <" + feature + "> . " +
                        "<" + wrapperA.getWrapper() + "> <" + SourceGraph.HAS_ATTRIBUTE.val() + "> ?a } }", T)
                        .nextSolution().get("a").asResource().getURI();

                String attB = RDFUtil.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                        "?a <" + Namespaces.owl.val() + "sameAs> <" + feature + "> . " +
                        "<" + wrapperB.getWrapper() + "> <" + SourceGraph.HAS_ATTRIBUTE.val() + "> ?a } }", T)
                        .nextSolution().get("a").asResource().getURI();

                ConjunctiveQuery mergedCQ = new ConjunctiveQuery();
                mergedCQ.getProjections().addAll(Ql.getProjections());
                mergedCQ.getProjections().addAll(Qr.getProjections());
                mergedCQ.getJoinConditions().addAll(Ql.getJoinConditions());
                mergedCQ.getJoinConditions().addAll(Qr.getJoinConditions());
                mergedCQ.getJoinConditions().add(new EquiJoin(attA,attB));
                mergedCQ.getWrappers().addAll(Ql.getWrappers());
                mergedCQ.getWrappers().addAll(Qr.getWrappers());

                res.add(mergedCQ);
            });
        });
        return res;
    }

    private static void getCoveringCQs(BasicPattern G, Dataset T, String c, ConjunctiveQuery currentCQ, Set<ConjunctiveQuery> candidateCQs, Set<ConjunctiveQuery> coveringCQs) {
        if (covering(currentCQ.getWrappers(),G,T)) {
            coveringCQs.add(currentCQ);
        }
        else if (!candidateCQs.isEmpty()) {
            ConjunctiveQuery CQ = candidateCQs.iterator().next();
            if (Sets.union(currentCQ.getProjections(),CQ.getProjections()).containsAll(currentCQ.getProjections()) &&
                    !Sets.union(currentCQ.getProjections(),CQ.getProjections()).equals(currentCQ.getProjections())) {
                Set<ConjunctiveQuery> CQs = combineCQs(currentCQ,CQ,c,c,T);
                CQs.forEach(Q -> {
                    getCoveringCQs(G,T,c,Q,Sets.difference(candidateCQs,Sets.newHashSet(CQ)),coveringCQs);
                });
            }
        }
    }

    public static Tuple3<Set<String>, BasicPattern, InfModel> parseSPARQL(String SPARQL, Dataset T) {
        // Compile the SPARQL using ARQ and generate its <pi,phi> representation
        Query q = QueryFactory.create(SPARQL);
        Op ARQ = Algebra.compile(q);

        Set<String> PI = Sets.newHashSet();
        ((OpTable)((OpJoin)((OpProject)ARQ).getSubOp()).getLeft()).getTable().rows().forEachRemaining(r -> {
            r.vars().forEachRemaining(v -> PI.add(r.get(v).getURI()));
        });

        BasicPattern PHI_p = ((OpBGP)((OpJoin)((OpProject)ARQ).getSubOp()).getRight()).getPattern();
        OntModel PHI_o_ontmodel = ontologyFromPattern(PHI_p);
        Reasoner reasoner = ReasonerRegistry.getTransitiveReasoner(); //RDFS entailment subclass+superclass
        InfModel PHI_o = ModelFactory.createInfModel(reasoner,PHI_o_ontmodel);

        return new Tuple3<>(PI,PHI_p,PHI_o);
    }

    public static Set<ConjunctiveAggregateQuery> rewriteToUnionOfConjunctiveAggregateQueries(Tuple3<Set<String>, BasicPattern, InfModel> queryStructure, Dataset T) {
        Set<String> PI = queryStructure._1;
        BasicPattern PHI_p = queryStructure._2;
        InfModel PHI_o = queryStructure._3;

        //Define G_virtual as a copy of G
        InfModel G_virtual = ModelFactory.createOntologyModel();
        PHI_p.getList().forEach(t ->
            addTriple(G_virtual, t.getSubject().getURI(), t.getPredicate().getURI(), t.getObject().getURI())
        );

        //Retrieve aggregable features (i.e., those that have an aggregation function)
        Set<String> aggregableFeatures = Sets.newHashSet();
        RDFUtil.runAQuery("SELECT ?f WHERE { GRAPH ?g {" +
            "?f <"+ GlobalGraph.HAS_AGGREGATION_FUNCTION.val()+"> ?t } }", T).forEachRemaining(f -> {
                String feature = f.get("f").toString();
                    if (PI.contains(feature)) aggregableFeatures.add(feature);
        });

        aggregableFeatures.forEach(f -> {
            //Get parent concept of f
            String parentConcept = RDFUtil.runAQuery("SELECT ?c WHERE { GRAPH ?g { " +
                    "?c <"+GlobalGraph.HAS_FEATURE.val()+"> <"+f+"> } }",T).next().get("c").toString();

            //identify the member concepts related to each parent concept (adjacentParents)
            Set<String> adjacentParents = Sets.newHashSet();
            RDFUtil.runAQuery("SELECT ?n WHERE { GRAPH ?g { ?n <"+GlobalGraph.PART_OF.val()+">+ <"+parentConcept+"> } } ", T)
                .forEachRemaining(adjacentParent -> adjacentParents.add(adjacentParent.get("n").toString()));

            Set<String> aggregableNeighbours = Sets.newHashSet();
            RDFUtil.runAQuery("SELECT ?n WHERE { GRAPH ?g { " +
                    "{ <"+parentConcept+"> ?p ?n . " +
                    "?n <"+GlobalGraph.PART_OF.val()+">+ ?top } UNION "+
                    "{ <"+parentConcept+"> ?p ?n . " +
                    "?bottom <"+ GlobalGraph.PART_OF.val()+">+ ?n } } }", T)
                .forEachRemaining(aggregableNeighbour -> aggregableNeighbours.add(aggregableNeighbour.get("n").toString()));

            //identify regular neighbours in the query
            Set<String> regularNeighbours = Sets.newHashSet();
            RDFUtil.runAQuery("SELECT ?n WHERE { GRAPH ?g {" +
                    "<"+parentConcept+"> ?p ?n } } ",PHI_o)
                .forEachRemaining(regularNeighbour -> {
                    String URI_regularNeighbour = regularNeighbour.get("n").toString();
                    if (!aggregableFeatures.contains(URI_regularNeighbour)
                            && !aggregableNeighbours.contains(URI_regularNeighbour)
                            && !adjacentParents.contains(URI_regularNeighbour)
                            && /*tweak to avoid including rdf:type*/!URI_regularNeighbour.contains(GlobalGraph.CONCEPT.val())) {
                        regularNeighbours.add(URI_regularNeighbour);
                        //expand with subclasses from the query pattern
                        RDFUtil.runAQuery("SELECT ?sc WHERE { " +
                            "?sc <"+Namespaces.rdfs.val()+"subClassOf>+ <"+URI_regularNeighbour+"> }",PHI_o)
                            .forEachRemaining(sc -> regularNeighbours.add(sc.get("sc").toString()));

                    }
                });

            adjacentParents.forEach(par -> {
                //Generate MatchQuery Qm
                BasicPattern matchQuery = new BasicPattern();
                Set<Tuple3<String,String,String>> patternTriples = Sets.newHashSet(); //used to avoid some duplicate triples that appear

                patternTriples.add(new Tuple3<>(par,GlobalGraph.HAS_FEATURE.val(),f));
                regularNeighbours.forEach(regularNeighbour -> {
                    RDFUtil.runAQuery("SELECT ?p WHERE { GRAPH ?g { <"+par+"> ?p <"+regularNeighbour+"> } }",T).forEachRemaining(prop -> {
                        patternTriples.add(new Tuple3<>(par,prop.get("p").toString(),regularNeighbour));
                    });
                });
                aggregableNeighbours.forEach(aggregableNeighbour -> {
                    RDFUtil.runAQuery("SELECT ?p ?y WHERE { GRAPH ?g { <"+par+"> ?p ?y . ?y <"+GlobalGraph.PART_OF.val()+">+ <"+aggregableNeighbour+"> } }",T)
                        .forEachRemaining(t -> {
                            patternTriples.add(new Tuple3<>(par,t.get("p").toString(),t.get("y").toString()));
                            //go up one at a time until in the top level (e.g., Hour)
                            //we rely on automatic expansion of IDs later
                            String currentLevel = t.get("y").toString();
                            while (!currentLevel.equals(aggregableNeighbour)) {
                                String top = RDFUtil.runAQuery("SELECT DISTINCT ?t WHERE { GRAPH ?g { <"+currentLevel+"> <"+GlobalGraph.PART_OF.val()+"> ?t } }",T)
                                    .next().get("t").toString();
                                patternTriples.add(new Tuple3<>(currentLevel,GlobalGraph.PART_OF.val(),top));
                                currentLevel = top;
                            }
                        });
                });
                patternTriples.forEach(triple -> addTriple(matchQuery,triple._1,triple._2,triple._3));

                //Call rewriteConjunctiveQuery with the matchQuery generated
                Tuple3<Set<String>,BasicPattern,InfModel> CQqueryStructure = new Tuple3<>(PI,matchQuery,ontologyFromPattern(matchQuery));
                /*Set<ConjunctiveQuery> UCQs = rewriteToUnionOfConjunctiveQueries(CQqueryStructure,T);

                UCQs.forEach(cq -> {
                    ConjunctiveAggregateQuery CAQ = new ConjunctiveAggregateQuery(cq);

                    //Traverse projected attributes, the feature goes to the aggregated function the rest of attributes go to the group by set
                    cq.getProjections().forEach(attribute -> {
                        if (runAQuery("SELECT ?f WHERE { GRAPH ?g { <"+attribute+"> <"+Namespaces.owl.val()+"sameAs> ?f } }",T)
                                .next().get("f").toString().equals(f)) {
                            CAQ.getAggregatedAttributes().add(new AggregatedAttribute(AggregationFunctions.SUM,attribute));
                        } else {
                            CAQ.getGroupBy().add(attribute);
                        }
                    });
                    System.out.println("From CQ : ");
                    System.out.println(cq);
                    System.out.println("Generated CAQ : ");
                    System.out.println(CAQ);
                });*/



            });


        });

        return Sets.newHashSet();
    }

    @SuppressWarnings("Duplicates")
    public static Tuple2<Integer,Set<ConjunctiveQuery>> rewriteToUnionOfConjunctiveQueries(Tuple3<Set<String>, BasicPattern, InfModel> queryStructure, Dataset T) {
        BasicPattern PHI_p = queryStructure._2;
        InfModel PHI_o = queryStructure._3;

        //Identify query-related concepts
        Graph<String,String> conceptsGraph = new SimpleGraph<>(String.class);
        PHI_p.getList().forEach(t -> {
            // Add only concepts so its easier to populate later the list of concepts
            if (!t.getPredicate().getURI().equals(GlobalGraph.HAS_FEATURE.val()) && !t.getObject().getURI().equals(Namespaces.sc.val()+"identifier")) {
                conceptsGraph.addVertex(t.getSubject().getURI());
                conceptsGraph.addVertex(t.getObject().getURI());
                conceptsGraph.addEdge(t.getSubject().getURI(), t.getObject().getURI(), UUID.randomUUID().toString());
            }
        });
        // This is required when only one concept is queried, where all edges are hasFeature
        if (conceptsGraph.vertexSet().isEmpty()) {
            conceptsGraph.addVertex(PHI_p.getList().get(0).getSubject().getURI());
        }


        // ***************************************
        // Intra-concept generation
        // ***************************************

        //This graph will hold "partialCQs", which are queries to retrieve the data for each concept
        Graph<KeyedTuple2<String,Set<ConjunctiveQuery>>,String> partialCQsGraph = new SimpleGraph<>(String.class);
        // 3 Identify queried features
        conceptsGraph.vertexSet().forEach(c -> {
            //System.out.println(c);
            Map<Wrapper,Set<String>> attsPerWrapper = Maps.newHashMap();
            ResultSet resultSetFeatures = RDFUtil.runAQuery("SELECT ?f " +
                    "WHERE {<"+c+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> ?f }",PHI_o);
        // 4 Unfold LAV mappings
            Set<String> F = Sets.newHashSet();
            resultSetFeatures.forEachRemaining(f -> F.add(f.get("f").asResource().getURI()));

            //Case when the Concept has no data, we need to identify the wrapper using the concept instead of the feature
            if (F.isEmpty()) {
                RDFUtil.runAQuery("SELECT ?g WHERE { GRAPH ?g { <" + c + "> <" + Namespaces.rdf.val() + "type" + "> <" + GlobalGraph.CONCEPT.val() + "> } }", T)
                    .forEachRemaining(wrapper -> {
                        String w = wrapper.get("g").toString();
                        //System.out.println("        "+w);
                        if (!w.equals(Namespaces.T.val()) && w.contains("Wrapper")/*last min bugfix*/) {
                            attsPerWrapper.putIfAbsent(new Wrapper(w), Sets.newHashSet());
                        }
                    });
            }
            F.forEach(f -> {
                //System.out.println("    "+f);
                ResultSet W = RDFUtil.runAQuery("SELECT ?g " +
                    "WHERE { GRAPH ?g { <" + c + "> <" + GlobalGraph.HAS_FEATURE.val() + "> <" + f + "> } }", T);
                W.forEachRemaining(wRes -> {
                    String w = wRes.get("g").asResource().getURI();
                    // Distinguish the ontology named graph
                    if (!w.equals(Namespaces.T.val()) && w.contains("Wrapper")/*last min bugfix*/) {
                        //System.out.println("        "+w);

                        ResultSet rsAttr = RDFUtil.runAQuery("SELECT ?a " +
                                "WHERE { GRAPH ?g { ?a <" + Namespaces.owl.val() + "sameAs> <" + f + "> . " +
                                "<" + w + "> <" + SourceGraph.HAS_ATTRIBUTE.val() + "> ?a } }", T);
                        String attribute = rsAttr.nextSolution().get("a").asResource().getURI();
                        //System.out.println("            "+attribute);
                        attsPerWrapper.putIfAbsent(new Wrapper(w), Sets.newHashSet());
                        Set<String> currentSet = attsPerWrapper.get(new Wrapper(w));
                        currentSet.add(attribute);
                        attsPerWrapper.put(new Wrapper(w), currentSet);
                    }
                });
            });

            Set<ConjunctiveQuery> candidateCQs = Sets.newHashSet();
            attsPerWrapper.keySet().forEach(w -> {
                ConjunctiveQuery Q = new ConjunctiveQuery(attsPerWrapper.get(w),Sets.newHashSet(),Sets.newHashSet(w));
                candidateCQs.add(Q);
            });

            Set<ConjunctiveQuery> coveringCQs = Sets.newHashSet();
            while (!candidateCQs.isEmpty()) {
                ConjunctiveQuery Q = candidateCQs.iterator().next();
                candidateCQs.remove(Q);

                BasicPattern phi = new BasicPattern();
                F.forEach(f -> phi.add(new Triple(new ResourceImpl(c).asNode(),
                        new PropertyImpl(GlobalGraph.HAS_FEATURE.val()).asNode(), new ResourceImpl(f).asNode())));
                getCoveringCQs(phi,T,c,Q,candidateCQs,coveringCQs);

            }
            partialCQsGraph.addVertex(new KeyedTuple2<>(c,coveringCQs));
        });

        //Add edges to the graph of partialCQs
        conceptsGraph.edgeSet().forEach(edge -> {
            KeyedTuple2<String,Set<ConjunctiveQuery>> source = partialCQsGraph.vertexSet().stream().filter(v -> v.equals(conceptsGraph.getEdgeSource(edge))).findFirst().get();
            KeyedTuple2<String,Set<ConjunctiveQuery>> target = partialCQsGraph.vertexSet().stream().filter(v -> v.equals(conceptsGraph.getEdgeTarget(edge))).findFirst().get();
            partialCQsGraph.addEdge(source, target, conceptsGraph.getEdge(source._1,target._1));
        });

        // ***************************************
        // Phase 3 : Inter-concept generation
        // ***************************************
        //Assumption here, acyclic graph
        int numberOfEdges = partialCQsGraph.edgeSet().size();
        int intermediateResults = 0;
        while (!partialCQsGraph.edgeSet().isEmpty()) {
            String edge = partialCQsGraph.edgeSet().iterator().next();//  edgeSet().stream().findAny().get();
            KeyedTuple2<String,Set<ConjunctiveQuery>> source = partialCQsGraph.getEdgeSource(edge);
            KeyedTuple2<String,Set<ConjunctiveQuery>> target = partialCQsGraph.getEdgeTarget(edge);

            KeyedTuple2<String,Set<ConjunctiveQuery>> joinedVertex = new KeyedTuple2<>(source._1+"-"+target._1, Sets.newHashSet());
            Set<List<ConjunctiveQuery>> cartesian = Sets.cartesianProduct(source._2,target._2);
            intermediateResults += cartesian.size();
            for (List<ConjunctiveQuery> CP : cartesian) {

                // 8 Merge CQs
                if (Collections.disjoint(CP.get(0).getWrappers(),CP.get(1).getWrappers())) {
                    //The partial CQs do not share any wrapper, must discover how to join them, this will add new equijoins

                    //First, let's check if the two sets of wrappers might generate non-minimal queries. If so, we can dismiss them.
                    if (minimal(Sets.union(CP.get(0).getWrappers(),CP.get(1).getWrappers()),PHI_p,T)) {

                        //This conceptSource and conceptTarget are obtained from the original graph, we can only join
                        //the two partialCQs using the IDs of these two concepts -- see the queries next
                        String conceptSource = conceptsGraph.getEdgeSource(edge);
                        String conceptTarget = conceptsGraph.getEdgeTarget(edge);

                        joinedVertex._2.addAll(combineCQs(CP.get(0),CP.get(1),conceptSource,conceptTarget,T));
                    }
                } else {
                    ConjunctiveQuery mergedCQ = new ConjunctiveQuery();
                    mergedCQ.getProjections().addAll(CP.get(0).getProjections());
                    mergedCQ.getProjections().addAll(CP.get(1).getProjections());
                    mergedCQ.getJoinConditions().addAll(CP.get(0).getJoinConditions());
                    mergedCQ.getJoinConditions().addAll(CP.get(1).getJoinConditions());
                    mergedCQ.getWrappers().addAll(CP.get(0).getWrappers());
                    mergedCQ.getWrappers().addAll(CP.get(1).getWrappers());

                    joinedVertex._2.add(mergedCQ);
                }

            }


            //Remove the processed edge
            partialCQsGraph.removeEdge(edge);
            //Add the new vertex to the graph
            partialCQsGraph.addVertex(joinedVertex);
            //Create edges to the new vertex from those neighbors of source and target
            Graphs.neighborListOf(partialCQsGraph,source).forEach(neighbor -> {
                if (!source.equals(neighbor)) {
                    String edgeConnectingSourceToNeighbor = partialCQsGraph.getEdge(new KeyedTuple2<>(source._1,null),new KeyedTuple2<>(neighbor._1,null));
                    partialCQsGraph.removeEdge(edgeConnectingSourceToNeighbor);
                    partialCQsGraph.addEdge(joinedVertex,neighbor,edgeConnectingSourceToNeighbor);
                }
            });
            Graphs.neighborListOf(partialCQsGraph,target).forEach(neighbor -> {
                if (!target.equals(neighbor)) {
                    String edgeConnectingTargetToNeighbor = partialCQsGraph.getEdge(new KeyedTuple2<>(target._1,null),new KeyedTuple2<>(neighbor._1,null));
                    partialCQsGraph.removeEdge(edgeConnectingTargetToNeighbor);
                    partialCQsGraph.addEdge(joinedVertex,neighbor,edgeConnectingTargetToNeighbor);
                }
            });
            partialCQsGraph.removeVertex(source);
            partialCQsGraph.removeVertex(target);
        }

       return new Tuple2<>((intermediateResults/(numberOfEdges==0?1:numberOfEdges)),partialCQsGraph.vertexSet().stream().findFirst().get()._2);
    }


}
