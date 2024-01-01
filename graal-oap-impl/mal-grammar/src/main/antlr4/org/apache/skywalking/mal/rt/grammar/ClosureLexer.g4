lexer grammar ClosureLexer;

DOT: '.';
STAR: '*';
SLASH: '/';
PLUS: '+';
MINUS: '-';
OPEN_PAREN: '(';
CLOSE_PAREN: ')';
OPEN_BRACKET: '[';
CLOSE_BRACKET: ']';
OPEN_BRACE: '{' ;
CLOSE_BRACE: '}';
COMMA: ',';
SEMI: ';';
EQUAL: '=';
QUESTION: '?';
COLON: ':';
NOT: '!';
AND: '&&';
OR: '||';
LESS: '<';
GREATER: '>';
EQUAL_EQUAL: '==';
NOT_EQUAL: '!=';
ARROW: '->';

IF: 'if';
RETURN: 'return';
ELSE_IF: 'else if';
ELSE: 'else';
CLASS_NAME: 'DetectPoint' | 'Layer' | 'ProcessRegistry' | 'K8sRetagType';

IDENTIFIER : [a-zA-Z_][a-zA-Z_0-9]* ;
NUMBER : '-'? [0-9]+ ('.' [0-9]+)? ;
LINE_COMMENT : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;
STRING : ('"' (~["\\] | '\\' .)* '"') | ('\'' (~['\\] | '\\' .)* '\'') ;
WS : [ \t\r\n]+ -> skip ;
