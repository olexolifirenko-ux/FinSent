/*
 * Copyright (c) 2000-2001 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */

package com.finsent.util.dataloader;

import java.util.ArrayList;

/**
 * This Tokenizer unlike java.util.StringTokenizer
 * returns empty strings between delimiters.
 *
 * @author Bogdan Vaskov
 */
public class Tokenizer //implements Enumeration
{
        private final String inputString_;
        private final String delimiters_;
        private final boolean isIncludeDelim_;
        private final boolean trim_;
        private int currentToken_ = 0;
        private ArrayList<String> tokens_ = null;

        private final static int STATE_START       = 0;
        private final static int STATE_TOKEN       = 1;
        private final static int STATE_DELIMITER   = 2;
        private final static int STATE_OPEN_QUOTE  = 3;
        private final static int STATE_CLOSE_QUOTE = 4;
        private final static int STATE_END         = 5;
        private final static int SYMBOL_ORDINAR    = 6;
        private final static int SYMBOL_DELIMITER  = 7;
        private final static int SYMBOL_QUOTE      = 8;
        private final static int SYMBOL_EOL        = 9;

        public Tokenizer(String str, String delimiters)
        {
            this(str, delimiters, false, true); // trim=true for backward compatibility
        }

        public Tokenizer(String str, String delimiters, boolean isIncludeDelim, boolean trim)
        {
            inputString_ = str;
            delimiters_ = delimiters;
            isIncludeDelim_ = isIncludeDelim;
            trim_ = trim;
        }

        public static String[] split(String str, String delim)
        {
            Tokenizer tt = new Tokenizer(str, delim);
            tt.countTokens();
            return tt.tokens_.toArray(new String[tt.tokens_.size()]);
        }

        public int countTokens()
        {
            if(tokens_ != null) return tokens_.size();

            tokens_ = new ArrayList<String>(10);
            int state = STATE_START;
            StringBuilder token = new StringBuilder();
            int position = 0;

            while(state != STATE_END)
            {
                int symbol = getSymbol(inputString_, position);
//System.out.print("STATE: "+state+", SYMBOL: "+symbol+"("+position+") TOKEN: ["+token+"] SIZE: "+tokens_.size());
                switch(state)
                {
                    case STATE_START:
                        switch(symbol)
                        {
                            case SYMBOL_ORDINAR:
                                token.append(inputString_.charAt(position));
                                state = STATE_TOKEN;
                                break;
                            case SYMBOL_DELIMITER:
                                tokens_.add("");
                                token.setLength(0);
                                token.append(inputString_.charAt(position));
                                state = STATE_DELIMITER;
                                break;
                            case SYMBOL_QUOTE:
                                state = STATE_OPEN_QUOTE;
                                break;
                            case SYMBOL_EOL:
                                state = STATE_END;
                                break;
                        }
                        break;

                    case STATE_TOKEN:
                        switch(symbol)
                        {
                            case SYMBOL_ORDINAR:
                            case SYMBOL_QUOTE:
                                token.append(inputString_.charAt(position));
                                break;
                            case SYMBOL_DELIMITER:
                                tokens_.add(token.toString());
                                token.setLength(0);
                                token.append(inputString_.charAt(position));
                                state = STATE_DELIMITER;
                                break;
                            case SYMBOL_EOL:
                                tokens_.add(token.toString());
                                state = STATE_END;
                                break;
                        }
                        break;

                    case STATE_DELIMITER:
                        if(isIncludeDelim_) tokens_.add(token.toString());
                        switch(symbol)
                        {
                            case SYMBOL_ORDINAR:
                                token.setLength(0);
                                token.append(inputString_.charAt(position));
                                state = STATE_TOKEN;
                                break;
                            case SYMBOL_DELIMITER:
                                tokens_.add("");
                                break;
                            case SYMBOL_QUOTE:
                                token.setLength(0);
                                state = STATE_OPEN_QUOTE;
                                break;
                            case SYMBOL_EOL:
                                tokens_.add("");
                                state = STATE_END;
                                break;
                        }
                        break;

                    case STATE_OPEN_QUOTE:
                        switch(symbol)
                        {
                            case SYMBOL_ORDINAR:
                            case SYMBOL_DELIMITER:
                                token.append(inputString_.charAt(position));
                                break;
                            case SYMBOL_QUOTE:
                                state = STATE_CLOSE_QUOTE;
                                break;
                            case SYMBOL_EOL:
                                tokens_.add(token.toString());
                                state = STATE_END;
                                break;
                        }
                        break;

                    case STATE_CLOSE_QUOTE:
                        switch(symbol)
                        {
                            case SYMBOL_ORDINAR:
                                token.append(inputString_.charAt(position));
                                state = STATE_TOKEN;
                                break;
                            case SYMBOL_DELIMITER:
                                tokens_.add(token.toString());
                                token.setLength(0);
                                token.append(inputString_.charAt(position));
                                state = STATE_DELIMITER;
                                    break;
                            case SYMBOL_QUOTE:
                                token.append('"');
                                state = STATE_OPEN_QUOTE;
                                break;
                            case SYMBOL_EOL:
                                tokens_.add(token.toString());
                                state = STATE_END;
                                break;
                        }
                        break;
                }
                position ++;
            }
            return tokens_.size();
        }

        public boolean hasMoreTokens()
        {
            if (tokens_ == null)
                countTokens();

            return (currentToken_ < tokens_.size());
        }

        public String nextToken()
        {
            if (tokens_ == null)
                countTokens();
            final String result = tokens_.get(currentToken_++);
            return trim_ ? result.trim() : result;
        }

        private int getSymbol(String s, int pos)
        {
            int sym;
            if(pos >= s.length())
                sym = SYMBOL_EOL;
            else if(s.charAt(pos) == '\"')
                sym = SYMBOL_QUOTE;
            else if(delimiters_.indexOf(s.charAt(pos)) >= 0)
                sym = SYMBOL_DELIMITER;
            else
                sym = SYMBOL_ORDINAR;
            return sym;
        }

//        public boolean hasMoreElements()
//        {
//            return hasMoreTokens();
//        }
//
//        public Object nextElement()
//        {
//            return nextToken();
//        }

    public static void main(String[] args)
    {
        String sIn = ",,,book, author, publication,,,date published,\"item1,item2\"";
        Tokenizer t = new Tokenizer(sIn, ",");
        int i =0;
        while(t.hasMoreTokens())
            System.out.println("token" + i++ + " = " + t.nextToken());

    }
}
