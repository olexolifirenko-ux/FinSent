/*
 * Copyright (c) 1998 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic class which is used for parsing command line arguments.
 * <p>
 * Class parses given command line arguments and maps them into options and
 * their values ( if any ) for quick access and checking.
 *
 * Also it supports positional args in the beginning, i.e. parameters without preceding "-".
 */
public class CmdArgParser
{
    private final PositionalArg[] positionalArgs_;
    private HashMap<String, String> args_ = new HashMap<>();
    private Map<String, int[]> validOptions_;
    private Map<String, String> dashOptionDescriptions_ = new HashMap<>();

    public CmdArgParser(String[] args, PositionalArg[] positionalArgs, DashOption[] options)
    {
        this(positionalArgs, args);
        setValidOptionMetaData(options);
    }

    public CmdArgParser(String[] args)
    {
        this(new PositionalArg[0], args);
    }

    /**
     * Constructor does parsing command line and mapping options to their values.
     *
     * @param     args given command line arguments ( like in
     *            <code>main()</code> method )
     */
    public CmdArgParser(PositionalArg[] positionalArgs, String[] args )
    {
        positionalArgs_ = positionalArgs;

        int index = 0;
        boolean onlyOptionalSinceNow = false;
        for (PositionalArg positionalArg : positionalArgs)
        {
            if (positionalArg.isRequired())
            {
                if (onlyOptionalSinceNow)
                {
                    error("Required '" + positionalArg.getName() + "' cannot follow after optional positional args");
                    return;
                }

                if (index >= args.length)
                {
                    error("Not enough args, required '" + positionalArg.getName() + "'");
                    return;
                }

                args_.put(positionalArg.getName(), args[index]);
            }
            else
            {
                onlyOptionalSinceNow = true;

                if (index >= args.length)
                    break;

                String arg = args[index];
                if (arg.startsWith(OPTIONMARK_STRING))
                {
                    break;
                }

                args_.put(positionalArg.getName(), arg);
            }
            ++index;
        }

        for ( int nextOpt, currOpt = index;
              currOpt < args.length; currOpt = nextOpt )
        {
            for ( nextOpt = currOpt + 1;
                  nextOpt < args.length && (args[nextOpt].length() == 0 ||
                  args[nextOpt].charAt(0) != OPTIONMARK); nextOpt++ )
                ; // Empty body
            String[] values = new String[nextOpt - currOpt - 1];
            for ( int i = 0; i < nextOpt - currOpt - 1; i++ )
                values[i] = args[i + currOpt + 1];
            options_.put(args[currOpt].substring(1), values);
        }
    }

    private void error(String message)
    {
        valid_ = false;
        detectedErrors_.add(message);
    }

    public void setValidOptionMetaData(DashOption...validOptions)
    {
        LinkedHashMap<String, int[]> adaptedOptions = new LinkedHashMap<>();
        for (DashOption option : validOptions)
        {
            dashOptionDescriptions_.put(option.getName(), option.getDescription());
            adaptedOptions.put(
                option.getName(),
                new int[] {
                    option.getRequiresNArgsAfterOption(),
                    option.isRequired() ? 1 : 0
                }
            );
        }

        setValidOptionMetaData(adaptedOptions);
    }

    public void setValidOptionMetaData(Map<String, int[]> validOptions)
    {
        setValidOptionMetaData(validOptions, true);
    }

   /**
    * Sets the valid options set and their attributes (requirement and
    * number of its values) to check format of the command line.
    * <p>
    * In the map key is an option name and value is and array int[2] where first
    * element is number of required arguments and the second element is a flag
    * indicating whether an option is required.
    *
    * @param validOptions <code>Map</code> object that maps each valid
    *                     option name to two-elements array: first element
    *                     is number of required option's values and second
    *                     one is 1 - if it is required option or 0 - otherwise
    */
    public
    void setValidOptionMetaData( Map<String, int[]> validOptions, boolean checkForExtraParams)
    {
        validOptions_ = validOptions;
        if (checkForExtraParams)
        {
            // Check whether given options are valid options
            for (String currOption : options_.keySet())
            {
                if (validOptions.get(currOption) == null)
                {
                    valid_ = false;
                    detectedErrors_.add("Unknown option '" + currOption + "'." );
                    //return;
                }
            }
        }

        // Check whether required options are given in command line and
        // each given option has appropriate number of values
        for (String nextValidOption : validOptions.keySet())
        {
            if ( options_.get(nextValidOption) == null )
            {
                if ( validOptions.get(nextValidOption)[1] == 1 )
                {
                    valid_ = false;
                    detectedErrors_.add("Mandatory option '" + nextValidOption + "' is not set." );
                    //return;
                }
            }
            else
            {
                int expectedValuesCount = validOptions.get(nextValidOption)[0];
                int actualValuesCount = options_.get(nextValidOption).length;
                if (expectedValuesCount > actualValuesCount)
                {
                    valid_ = false;
                    detectedErrors_.add("Option '" + nextValidOption + "' requires " + expectedValuesCount + " value(s), but " + actualValuesCount + ((actualValuesCount > 1) ? " are" : " is") + " set." );
                    //return;
                }
            }
        }
        //valid_ = true; // was set during instance initialization
    } // CmdArgParser.setValidOptionMetaData()

// Instance accessors

    /**
     * Checks whether given in command line options are valid options.
     *
     * @return    true - if given in command line options are valid options.
     */
    public
    boolean isValid()
    {
        return valid_;
    } // CmdArgParser.isValid()

    /**
     * Checks whether option was given in command line.
     *
     * @param     option checked option
     * @return    true - if the option was given in command line
     * @exception IllegalArgumentException if the given in command line options
     *            are not allowed options
     */
    public
    boolean isOptionSet( String option )
        throws IllegalArgumentException
    {
        if ( !valid_ )
            throw new IllegalArgumentException(ERRORMSG);
        return options_.containsKey(option);
    } // CmdArgParser.isOptionSet()

    /**
     * Checks whether option was given in command line and returns values of
     * the option.
     *
     * @param     option checked option
     * @return    null - if the option was not given in command line or
     *            array of string representations the values of the given
     *            option otherwise.
     * @exception IllegalArgumentException if the given in command line options
     *            are not allowed options
     */
    public
    String[] getOptionValues( String option )
        throws IllegalArgumentException
    {
        if ( !valid_ )
            throw new IllegalArgumentException(ERRORMSG);
        return options_.get(option);
    } // CmdArgParser.getOptionValues()

    /**
     * Checks whether option was given in command line and returns values of
     * the option. Otherwise returns provided default values.
     *
     * @param     option checked option
     * @param     defaultValues
     * @return    values
     * @exception IllegalArgumentException if the given in command line options
     *            are not allowed options
     */
    public
    String[] getOptionValues( String option, String[] defaultValues )
        throws IllegalArgumentException
    {
        String[] values = getOptionValues(option);
        if (null == values)
            return defaultValues;
        else
            return values;
    } // CmdArgParser.getOptionValues()

    /**
     * Checks whether option was given in command line and returns the first
     * value of the option.
     *
     * @param     option checked option
     * @return    null - if the option was not given in command line or
     *            string representations the value of the given option otherwise
     * @exception IllegalArgumentException if the given in command line options
     *            are not allowed options
     */
    public
    String getOptionValue( String option )
        throws IllegalArgumentException
    {
        String[] values = getOptionValues(option);
        return ( (values == null) || (values.length == 0) ) ? null : values[0];
    } // CmdArgParser.getOptionValue()

    /**
     * Checks whether option was given in command line and returns the
     * value of the option. Otherwise returns provided default value.
     *
     * @param     option checked option
     * @param     defaultValue
     * @return    value
     * @exception IllegalArgumentException if the given in command line options
     *            are not allowed options
     */
    public
    String getOptionValue( String option, String defaultValue )
        throws IllegalArgumentException
    {
        String value = getOptionValue(option);
        if (null == value)
            return defaultValue;
        else
            return value;
    }

    public String generateValidSyntax()
    {
        return generateValidSyntax(positionalArgs_, validOptions_, dashOptionDescriptions_);
    }

    public static
    String generateValidSyntax( Map<String, int[]> validOptions )
    {
        return generateValidSyntax(new PositionalArg[0], validOptions, null);
    }

    /**
    * Generates a usage string based on metadata represented in
    * <code>Map</code> object. Generated string will be in the format:
    * <p><blockquote><pre>
    * -option1 <value1> <value2> [-option2 <value1>] -option3 -option4 <value1>
    * </pre></blockquote><p>
    * given that metadata is
    * <p><blockquote><pre>
    * "option1" - 2, 1
    * "option2" - 1, 0
    * "option3" - 0, 1
    * "option4" - 1, 1
    * </pre></blockquote><p>
    *
    * @param dashOptions <code>Map</code> object that maps each valid
    *                     option name to two-elements array: first element
    *                     is number of required option's values and second
    *                     one is 1 - if it is required option or 0 - otherwise
    */
    public static
    String generateValidSyntax(PositionalArg[] positionalArgs, Map<String, int[]> dashOptions, Map<String, String> dashOptionDescriptions)
    {
        // Markers of optional options
        final char OPTIONALBEGIN = '[';
        final char OPTIONALEND   = ']';
        // String represents 'value'
        final String VALUE      = "value";
        final char   VALUEBEGIN = '<';
        final char   VALUEEND   = '>';

        StringBuffer usage = new StringBuffer(" ");

        int optionalArgCount = 0;
        for (PositionalArg positionalArg : positionalArgs)
        {
            usage.append(' ');
            if (!positionalArg.isRequired())
            {
                usage.append(OPTIONALBEGIN);
                ++optionalArgCount;
            }
            usage.append(VALUEBEGIN);
            usage.append(positionalArg.getName());
            usage.append(VALUEEND);
        }

        for (int i = 0; i < optionalArgCount; i++)
        {
            usage.append(OPTIONALEND);
        }

        for (String option : dashOptions.keySet())
        {
            int[] optAttr = dashOptions.get(option);
            usage.append(' ');
            if ( optAttr[1] == 0 )
                usage.append(OPTIONALBEGIN);
            usage.append(OPTIONMARK).append(option);
            for ( int i = 1; i <= optAttr[0]; i++ )
                usage.append(' ').append(VALUEBEGIN).append(VALUE).append(i).
                      append(VALUEEND);
            if ( optAttr[1] == 0 )
                usage.append(OPTIONALEND);
        }

        if (dashOptionDescriptions != null)
        {
            usage.append("\n  where:");
            for (PositionalArg positionalArg : positionalArgs)
            {
                String name = positionalArg.getName();
                String description = positionalArg.getDescription();
                if (description == null)
                    description = name;

                usage
                    .append("\n    ")
                    .append(name)
                    .append(" - ")
                    .append(description);
            }
            for (String option : dashOptions.keySet())
            {
                String description = dashOptionDescriptions.get(option);
                if (description == null)
                    description = option;

                usage
                    .append("\n    ")
                    .append(option)
                    .append(" - ")
                    .append(description);
            }
        }

        return usage.toString();
    }

    /**
     * Returns the list of errors detected so far.
     * If no errors are detected, the array size is 0.
     */
    public String[] getErrors()
    {
        String [] result = new String[detectedErrors_.size()];
        detectedErrors_.toArray(result);
        return result;
    }

    /**
     * Returns the formatted string with all errors detected so far.
     * If no errors are detected, the string is empty.
     */
    public String getErrorsString()
    {
        String result = "";
        String []allErrors = getErrors();
        if (allErrors.length > 0)
        {
            result =
                ERROR_MESSAGE_HEADER +
                ERRORS_SEPARATOR +
                UtilityFunctions.arrayToString(allErrors, ERRORS_SEPARATOR);
        }
        return result;
    }

// Instance variables

    // Map options to their values
    private HashMap<String, String[]> options_ = new HashMap<>(3, 1f);
    // Validation flag
    private boolean valid_   = true;
    // Errors detected so far
    private ArrayList<String> detectedErrors_ = new ArrayList<>();

    // Character that options begin with
    private static final char OPTIONMARK = '-';
    private static final String OPTIONMARK_STRING = String.valueOf(OPTIONMARK);
    // Error message passed within exception that raises class
    private static final String ERRORMSG   = "Error in command line.";
    // Error message header
    private static final String ERROR_MESSAGE_HEADER = "Command parameters error(s) detected:";
    // Character that options begin with
    private static final String ERRORS_SEPARATOR = "\n - ";

    public static PositionalArg[] positionalArgs(PositionalArg...args)
    {
        return args;
    }

    public static PositionalArg required(String name, String description)
    {
        return new PositionalArg(name, true, description);
    }

    public static PositionalArg optional(String name, String description)
    {
        return new PositionalArg(name, false, description);
    }

    public static DashOption[] dashOptions(DashOption...options)
    {
        return options;
    }

    public static DashOption required(String name, int requiresNArgsAfterOption, String description)
    {
        return new DashOption(name, requiresNArgsAfterOption, true, description);
    }

    public static DashOption optional(String name, int requiresNArgsAfterOption, String description)
    {
        return new DashOption(name, requiresNArgsAfterOption, false, description);
    }

    public static DashOption flag(String name, String description)
    {
        return new DashOption(name, 0, false, description);
    }

    public String getPositionalArg(String arg)
    {
        return args_.get(arg);
    }

    public static class PositionalArg
    {
        private final String name_;
        private final boolean required_;
        private String description_;

        PositionalArg(String name, boolean required, String description)
        {
            name_ = name;
            required_ = required;
            description_ = description;
        }

        public String getName()
        {
            return name_;
        }

        public boolean isRequired()
        {
            return required_;
        }

        public String getDescription()
        {
            return description_;
        }
    }

    public static class DashOption
    {
        private final String name_;
        private final int requiresNArgsAfterOption_;
        private final boolean required_;
        private String description_;

        DashOption(String name, int requiresNArgsAfterOption, boolean required, String description)
        {
            name_ = name;
            requiresNArgsAfterOption_ = requiresNArgsAfterOption;
            required_ = required;
            description_ = description;
        }

        public String getName()
        {
            return name_;
        }

        public String getDescription()
        {
            return description_;
        }

        public int getRequiresNArgsAfterOption()
        {
            return requiresNArgsAfterOption_;
        }

        public boolean isRequired()
        {
            return required_;
        }
    }
}
