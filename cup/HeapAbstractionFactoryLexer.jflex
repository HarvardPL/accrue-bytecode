//From root:
//java -jar lib/jflex.jar -d generated-sources/analysis/pointer/analyses/parser cup/HeapAbstractionFactoryLexer.jflex 
package analysis.pointer.analyses.parser;

import java_cup.runtime.Symbol;

%%

%class HeapAbstractionFactoryLexer
%cup
%implements sym
%line
%column
%state STRING

%{
  StringBuffer string = new StringBuffer();

  private Symbol symbol(int sym) {
    return new Symbol(sym, yyline+1, yycolumn+1);
  }
  
  private Symbol symbol(int sym, Object val) {
    return new Symbol(sym, yyline+1, yycolumn+1, val);
  }
  
  private void error(String message) {
    throw new RuntimeException("Invalid token at line " + (yyline+1) + ", column " + (yycolumn+1) + ": " + message);
  }
%} 

white_space = [ \t\f]
int_literal = [0-9]+
identifier = [:jletter:] [:jletterdigit:]*

%%

<YYINITIAL> {
    /* HeapAbstractionFactory short names */
    "full"                        { return symbol(FULL); }
    "type"                         { return symbol(TYPE); }
    "scs"                        { return symbol(SCS); }
    "cs"                         { return symbol(CS); }
    
    /* class name */
    {identifier}                 { return symbol(IDENTIFIER, yytext()); }

    /* literals */
    {int_literal}                { return symbol(INT_LITERAL, yytext()); }


    /* separators */
    ","               { return symbol(COMMA); }
    "("               { return symbol(LPAR); }
    ")"               { return symbol(RPAR); }
    "["               { return symbol(LBRACK); }
    "]"               { return symbol(RBRACK); }
    "."               { return symbol(DOT); }

    /* ignore white space */
    {white_space}                { /* ignore */ }
}

/* error fallback */
[^]|\n                             { error("Illegal character <"+ yytext()+">"); }

