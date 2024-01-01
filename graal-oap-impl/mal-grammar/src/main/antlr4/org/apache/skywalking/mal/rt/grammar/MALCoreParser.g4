parser grammar MALCoreParser;

options { tokenVocab=MALCoreLexer; }

malExpression: mainExpr;

closure
 : OPEN_BRACE (CLOSURE_CONTENT|OPEN_CURLY|CLOSE_CURLY)* CLOSE_CURLY
 ;


varDeclare
 : IDENTIFIER IDENTIFIER (EQUAL groovyExpr)? SEMI?
 ;

mainExpr:
        mainExpr DOT methodCall
        | methodCall
        | mainExpr op=(STAR | SLASH) mainExpr
        | mainExpr op=(PLUS | MINUS) mainExpr
        | IDENTIFIER
        | NUMBER
        | OPEN_PAREN mainExpr CLOSE_PAREN
    ;

groovyExpr:
        IDENTIFIER DOT IDENTIFIER
    | CLASS_NAME DOT groovyMethodCall
    | groovyExpr DOT groovyMethodCall
    | groovyMethodCall
    | IDENTIFIER OPEN_BRACKET groovyExpr CLOSE_BRACKET
    | MINUS groovyExpr
    | NOT groovyExpr
    | groovyExpr op=(STAR | SLASH) groovyExpr
    | groovyExpr op=(PLUS | MINUS) groovyExpr
    | groovyExpr (EQUAL_EQUAL | LESS | GREATER | NOT_EQUAL) groovyExpr
    | groovyExpr (AND | OR) groovyExpr
    | groovyExpr QUESTION groovyExpr COLON groovyExpr
    | IDENTIFIER
    | STRING
    | NUMBER
    | OPEN_PAREN groovyExpr CLOSE_PAREN
    ;

methodCall:
    IDENTIFIER OPEN_PAREN LE? BY? paramList? CLOSE_PAREN
    ;

groovyMethodCall:
    IDENTIFIER OPEN_PAREN groovyParamList? CLOSE_PAREN
    ;

block: OPEN_BRACE stat* CLOSE_BRACE
    ;

stat:
       block
    | varDeclare
    | IF OPEN_PAREN groovyExpr CLOSE_PAREN block (ELSE_IF OPEN_PAREN groovyExpr CLOSE_PAREN block)* (ELSE block)?
    | RETURN groovyExpr? SEMI?
    | groovyExpr EQUAL groovyExpr
    | groovyExpr
    ;

param: NUMBER
  | closure
  | OPEN_BRACKET stringList CLOSE_BRACKET
  | K8sRetagType
  | OPEN_BRACKET percentiles CLOSE_BRACKET
  | LAYER
  | STRING
  | DOWNSAMPLING_TYPE
  ;

groovyParamList: groovyExpr (COMMA groovyExpr)*;
percentiles : NUMBER (COMMA NUMBER)* ;

paramList: param (COMMA param)* ;

stringList: STRING (COMMA STRING)*;
