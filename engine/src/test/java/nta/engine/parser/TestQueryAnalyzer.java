package nta.engine.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import nta.catalog.CatalogService;
import nta.catalog.FunctionDesc;
import nta.catalog.Options;
import nta.catalog.Schema;
import nta.catalog.TCatUtil;
import nta.catalog.TableDesc;
import nta.catalog.TableDescImpl;
import nta.catalog.TableMeta;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.catalog.proto.CatalogProtos.FunctionType;
import nta.catalog.proto.CatalogProtos.IndexMethod;
import nta.catalog.proto.CatalogProtos.StoreType;
import nta.datum.DatumFactory;
import nta.engine.Context;
import nta.engine.NtaTestingUtility;
import nta.engine.QueryContext;
import nta.engine.exec.eval.EvalNode;
import nta.engine.exec.eval.EvalNode.Type;
import nta.engine.exec.eval.TestEvalTree.TestSum;
import nta.engine.parser.QueryBlock.JoinClause;
import nta.engine.parser.QueryBlock.SortKey;
import nta.engine.planner.JoinType;
import nta.engine.query.exception.InvalidQueryException;
import nta.storage.Tuple;
import nta.storage.VTuple;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This unit test examines the correctness of QueryAnalyzer that analyzes 
 * an abstract syntax tree built from Antlr and generates a QueryBlock instance.
 * 
 * @author Hyunsik Choi
 * 
 * @see QueryAnalyzer
 * @see QueryBlock
 */
public class TestQueryAnalyzer {
  private static NtaTestingUtility util;
  private static CatalogService cat = null;
  private static Schema schema1 = null;
  private static QueryAnalyzer analyzer = null;
  private static QueryContext.Factory factory = null;
  
  @BeforeClass
  public static void setUp() throws Exception {
    util = new NtaTestingUtility();
    util.startMiniZKCluster();
    util.startCatalogCluster();
    cat = util.getMiniCatalogCluster().getCatalog();
    
    schema1 = new Schema();
    schema1.addColumn("id", DataType.INT);
    schema1.addColumn("name", DataType.STRING);
    schema1.addColumn("score", DataType.INT);
    schema1.addColumn("age", DataType.INT);
    
    Schema schema2 = new Schema();
    schema2.addColumn("id", DataType.INT);
    schema2.addColumn("people_id", DataType.INT);
    schema2.addColumn("dept", DataType.STRING);
    schema2.addColumn("year", DataType.INT);
    
    Schema schema3 = new Schema();
    schema3.addColumn("id", DataType.INT);
    schema3.addColumn("people_id", DataType.INT);
    schema3.addColumn("class", DataType.STRING);
    schema3.addColumn("branch_name", DataType.STRING);

    TableMeta meta = TCatUtil.newTableMeta(schema1, StoreType.CSV);
    TableDesc people = new TableDescImpl("people", meta, new Path("file:///"));
    cat.addTable(people);
    
    TableDesc student = TCatUtil.newTableDesc("student", schema2, StoreType.CSV,
        new Options(),
        new Path("file:///"));
    cat.addTable(student);
    
    TableDesc branch = TCatUtil.newTableDesc("branch", schema3, StoreType.CSV,
        new Options(),
        new Path("file:///"));
    cat.addTable(branch);
    
    FunctionDesc funcMeta = new FunctionDesc("sumtest", TestSum.class,
        FunctionType.GENERAL, DataType.INT, 
        new DataType [] {DataType.INT});

    cat.registerFunction(funcMeta);
    
    analyzer = new QueryAnalyzer(cat);
    factory = new QueryContext.Factory(cat);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    util.shutdownCatalogCluster();
    util.shutdownMiniZKCluster();
  }

  private String[] QUERIES = { 
      "select id, name, score, age from people", // 0
      "select name, score, age from people where score > 30", // 1
      "select name, score, age from people where 3 + 5 * 3", // 2
      "select age, sumtest(score) as total from people group by age having sumtest(score) > 30", // 3
      "select p.id, s.id, score, dept from people as p, student as s where p.id = s.id", // 4
      "select name, score from people order by score asc, age desc null first", // 5
      // only expr
      "select 7 + 8", // 6
      // create table test
      "store1 := select name, score from people order by score asc, age desc null first",// 7
      // create table test
      "create table store2 as select name, score from people order by score asc, age desc null first", // 8
      // create index
      "create unique index score_idx on people using hash (score, age desc null first) with ('fillfactor' = 70)", // 9
      // create table def
      "create table table1 (name string, age int, earn long, score float) using csv location '/tmp/data' with ('csv.delimiter'='|')" // 10     
  };

  public static NQLParser parseExpr(final String expr) {
    ANTLRStringStream input = new ANTLRStringStream(expr);
    NQLLexer lexer = new NQLLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    NQLParser parser = new NQLParser(tokens);
    return parser;
  }

  public final void testNewEvalTree() {    
    Tuple tuples[] = new Tuple[1000000];
    for (int i = 0; i < 1000000; i++) {
      tuples[i] = new VTuple(4);
      tuples[i].put(
          DatumFactory.createInt(i),
          DatumFactory.createString("hyunsik_" + i),
          DatumFactory.createInt(i + 500),
          DatumFactory.createInt(i));
    }
    
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[0]);

    assertEquals(1, block.getNumFromTables());

    ctx = factory.create();
    block = (QueryBlock) analyzer.parse(ctx, QUERIES[2]);
    EvalNode expr = block.getWhereCondition();

    long start = System.currentTimeMillis();
    for (int i = 0; i < tuples.length; i++) {
      expr.eval(schema1, tuples[i]);
    }
    long end = System.currentTimeMillis();

    System.out.println("elapsed time: " + (end - start));
  }
 
  @Test
  public final void testSelectStatement() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[0]);
    
    assertEquals(1, block.getFromTables().length);
    assertEquals("people", block.getFromTables()[0].getTableId());
    ctx = factory.create();
    block = (QueryBlock) analyzer.parse(ctx, QUERIES[3]);    
    // TODO - to be more regressive
  }
  
  @Test
  public final void testSelectStatementWithAlias() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[4]);
    assertEquals(2, block.getFromTables().length);
    assertEquals("people", block.getFromTables()[0].getTableId());
    assertEquals("student", block.getFromTables()[1].getTableId());
  }
  
  @Test
  public final void testOrderByClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[5]);
    testOrderByCluse(block);
  }
  
  private static final void testOrderByCluse(QueryBlock block) {
    assertEquals(2, block.getSortKeys().length);
    assertEquals("people.score", block.getSortKeys()[0].getSortKey().getQualifiedName());
    assertEquals(true, block.getSortKeys()[0].isAscending());
    assertEquals(false, block.getSortKeys()[0].isNullFirst());
    assertEquals("people.age", block.getSortKeys()[1].getSortKey().getQualifiedName());
    assertEquals(false, block.getSortKeys()[1].isAscending());
    assertEquals(true, block.getSortKeys()[1].isNullFirst());
  }
  
  @Test
  public final void testCreateTableAsSelect() {
    Context ctx = factory.create();
    CreateTableStmt stmt = (CreateTableStmt) analyzer.parse(ctx, QUERIES[7]);
    assertEquals("store1", stmt.getTableName());
    testOrderByCluse(stmt.getSelectStmt());
    
    ctx = factory.create();
    stmt = (CreateTableStmt) analyzer.parse(ctx, QUERIES[8]);
    assertEquals("store2", stmt.getTableName());
    testOrderByCluse(stmt.getSelectStmt());
  }
  
  @Test
  public final void testCreateTableDef() {
    Context ctx = factory.create();
    CreateTableStmt stmt = (CreateTableStmt) analyzer.parse(ctx, QUERIES[10]);
    assertEquals("table1", stmt.getTableName());
    Schema def = stmt.getSchema();
    assertEquals("name", def.getColumn(0).getColumnName());
    assertEquals(DataType.STRING, def.getColumn(0).getDataType());
    assertEquals("age", def.getColumn(1).getColumnName());
    assertEquals(DataType.INT, def.getColumn(1).getDataType());
    assertEquals("earn", def.getColumn(2).getColumnName());
    assertEquals(DataType.LONG, def.getColumn(2).getDataType());
    assertEquals("score", def.getColumn(3).getColumnName());
    assertEquals(DataType.FLOAT, def.getColumn(3).getDataType());    
    assertEquals(StoreType.CSV, stmt.getStoreType());    
    assertEquals("/tmp/data", stmt.getPath().toString());
    assertTrue(stmt.hasOptions());
    assertEquals("|", stmt.getOptions().get("csv.delimiter"));
  }
  
  @Test 
  public final void testCreateIndex() {
    Context ctx = factory.create();
    CreateIndexStmt stmt = (CreateIndexStmt) analyzer.parse(ctx, QUERIES[9]);
    assertEquals("score_idx", stmt.getIndexName());
    assertTrue(stmt.isUnique());
    assertEquals("people", stmt.getTableName());
    assertEquals(IndexMethod.HASH, stmt.getMethod());
    
    SortKey [] sortKeys = stmt.getSortSpecs();
    assertEquals(2, sortKeys.length);
    assertEquals("score", sortKeys[0].getSortKey().getColumnName());
    assertEquals(DataType.INT, sortKeys[0].getSortKey().getDataType());
    assertEquals("age", sortKeys[1].getSortKey().getColumnName());
    assertEquals(DataType.INT, sortKeys[1].getSortKey().getDataType());
    assertEquals(false, sortKeys[1].isAscending());
    assertEquals(true, sortKeys[1].isNullFirst());
    
    assertTrue(stmt.hasParams());
    assertEquals("70", stmt.getParams().get("fillfactor"));
  }
  
  @Test
  public final void testOnlyExpr() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[6]);
    EvalNode node = block.getTargetList()[0].getEvalTree();
    assertEquals(Type.PLUS, node.getType());
  }
  
  private String [] INVALID_QUERIES = {
      "select * from invalid", // 0 - when a given table does not exist
      "select time, age from people", // 1 - when a given column does not exist
      "select age from people group by age2" // 2 - when a grouping field does not eixst
  };
  @Test(expected = InvalidQueryException.class)
  public final void testNoSuchTables()  {
    Context ctx = factory.create();
    analyzer.parse(ctx, INVALID_QUERIES[0]);
  }
  
  @Test(expected = InvalidQueryException.class)
  public final void testNoSuchFields()  {
    Context ctx = factory.create();
    analyzer.parse(ctx, INVALID_QUERIES[1]);
  }
  
  @Test
  public final void testGroupByClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, QUERIES[3]);
    assertEquals("people.age", block.getGroupFields()[0].getQualifiedName());
  }
  
  @Test(expected = InvalidQueryException.class)
  public final void testInvalidGroupFields() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, INVALID_QUERIES[2]);
    assertEquals("age", block.getGroupFields()[0].getQualifiedName());
  }
  
  static String [] JOINS = {
    "select p.id, name, branch_name from people as p natural join student natural join branch", // 0
    "select name, dept from people as p inner join student as s on p.id = s.people_id", // 1
    "select name, dept from people as p inner join student as s using (p.id)", // 2
    "select p.id, name, branch_name from people as p cross join student cross join branch", // 3
    "select p.id, dept from people as p left outer join student as s on p.id = s.people_id", // 4
    "select p.id, dept from people as p right outer join student as s on p.id = s.people_id", // 5
    "select p.id, dept from people as p join student as s on p.id = s.people_id", // 6
    "select p.id, dept from people as p left join student as s on p.id = s.people_id", // 7
    "select p.id, dept from people as p right join student as s on p.id= s.people_id" // 8
  };
  
  @Test
  public final void testNaturalJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[0]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.NATURAL, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());    
    assertTrue(join.hasRightJoin());
    
    assertFalse(join.getRightJoin().getLeft().hasAlias());
    assertEquals("student", join.getRightJoin().getLeft().getTableId());
    assertEquals("branch", join.getRightJoin().getRight().getTableId());
  }
  
  @Test
  public final void testInnerJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[1]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
    
    ctx = factory.create();
    block = (QueryBlock) analyzer.parse(ctx, JOINS[2]);
    join = block.getJoinClause();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinColumns());
    assertEquals("id", join.getJoinColumns()[0].getColumnName());
  }
  
  @Test
  public final void testJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[6]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.INNER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
  }
  
  @Test
  public final void testCrossJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[3]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.CROSS_JOIN, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());    
    assertTrue(join.hasRightJoin());
    
    assertFalse(join.getRightJoin().getLeft().hasAlias());
    assertEquals("student", join.getRightJoin().getLeft().getTableId());
    assertEquals("branch", join.getRightJoin().getRight().getTableId());
  }
  
  @Test
  public final void testLeftOuterJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[4]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.LEFT_OUTER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
  }
  
  @Test
  public final void testLeftJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[7]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.LEFT_OUTER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
  }
  
  @Test
  public final void testRightOuterJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[5]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.RIGHT_OUTER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
  }
  
  @Test
  public final void testRightJoinClause() {
    Context ctx = factory.create();
    QueryBlock block = (QueryBlock) analyzer.parse(ctx, JOINS[8]);
    JoinClause join = block.getJoinClause();
    assertEquals(JoinType.RIGHT_OUTER, join.getJoinType());
    assertEquals("people", join.getLeft().getTableId());
    assertEquals("p", join.getLeft().getAlias());
    assertEquals("student", join.getRight().getTableId());
    assertEquals("s", join.getRight().getAlias());
    assertTrue(join.hasJoinQual());
    assertEquals(EvalNode.Type.EQUAL, join.getJoinQual().getType());
  }
}