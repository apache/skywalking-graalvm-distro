parser grammar ClosureParser;
options { tokenVocab=ClosureLexer; }

closure
 : OPEN_BRACE '('? closureParam? ')'? ARROW? closureContent CLOSE_BRACE
 ;

closureParam
 : idList
 | varDeclareList
 ;

varDeclare
 : IDENTIFIER IDENTIFIER (EQUAL groovyExpr)? SEMI?
 ;

varDeclareList
 : varDeclare (COMMA varDeclare)*
 ;

idList
 : IDENTIFIER (COMMA IDENTIFIER)*
 ;

closureContent
 : (stat SEMI?)*
 ;

groovyExpr:
    IDENTIFIER propertyCall=DOT IDENTIFIER
 | CLASS_NAME staticCall=DOT groovyMethodCall
 | groovyExpr methodCall=DOT groovyMethodCall
 | simpleMethodCall=groovyMethodCall
 | IDENTIFIER arrayCall=OPEN_BRACKET groovyExpr CLOSE_BRACKET
// | MINUS groovyExpr
 | NOT groovyExpr
 | groovyExpr op=(STAR|SLASH) groovyExpr
 | groovyExpr op=(PLUS|MINUS) groovyExpr
 | groovyExpr op=(EQUAL_EQUAL | LESS | GREATER | NOT_EQUAL) groovyExpr
 | groovyExpr op=(AND | OR) groovyExpr
 | groovyExpr QUESTION groovyExpr COLON groovyExpr
 | IDENTIFIER
 | STRING
 | NUMBER
 | OPEN_PAREN groovyExpr CLOSE_PAREN
 ;


groovyMethodCall:
 IDENTIFIER OPEN_PAREN groovyParamList? CLOSE_PAREN
 ;
block:  OPEN_BRACE stat* CLOSE_BRACE
    ;

stat:
       varDeclare
    |   IF OPEN_PAREN ifCondition=groovyExpr CLOSE_PAREN block (ELSE_IF OPEN_PAREN elseIFCondition+=groovyExpr CLOSE_PAREN block)* (ELSE block)?
    |   RETURN groovyExpr? SEMI?
    |   groovyExpr EQUAL groovyExpr
    |   groovyExpr
    ;


groovyParamList: groovyExpr (COMMA groovyExpr)*;
