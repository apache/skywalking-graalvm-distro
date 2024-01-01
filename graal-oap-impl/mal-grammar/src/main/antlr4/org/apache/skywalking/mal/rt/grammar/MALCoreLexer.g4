lexer grammar MALCoreLexer;

// 特殊符号
DOT: '.';
STAR: '*';
SLASH: '/';
PLUS: '+';
MINUS: '-';
OPEN_PAREN: '(';
CLOSE_PAREN: ')';
OPEN_BRACKET: '[';
CLOSE_BRACKET: ']';
OPEN_BRACE: '{' -> pushMode(CLOSURE);
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

// 关键字
IF: 'if';
RETURN: 'return';
LE: 'le';
BY: 'by';
ELSE_IF: 'else if';
ELSE: 'else';

// 原有的词法符号
LAYER : 'Layer.' LAYER_TYPE ;
LAYER_TYPE : 'HTTP' | 'MYSQL';
DETECTPOINT : 'CLIENT' | 'SERVER' ;
DOWNSAMPLING_TYPE : 'LATEST' | 'AVG' | 'SUM';
CLASS_NAME: 'DetectPoint' | 'Layer' | 'ProcessRegistry' | 'K8sRetagType';
K8sRetagType : 'K8sRetagType.Pod2Service';
IDENTIFIER : [a-zA-Z_][a-zA-Z_0-9]* ;
NUMBER : '-'? [0-9]+ ('.' [0-9]+)? ;
LINE_COMMENT : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT : '/*' .*? '*/' -> skip;
STRING : ('"' (~["\\] | '\\' .)* '"') | ('\'' (~['\\] | '\\' .)* '\'') ;
WS : [ \t\r\n]+ -> skip ;


mode CLOSURE;
OPEN_CURLY : '{' -> pushMode(CLOSURE);
CLOSE_CURLY : '}' -> popMode; // 遇到 '}' 时返回到默认模式
CLOSURE_CONTENT : ~[{}]+ ; // 匹配除了 '}' 之外的任何字符