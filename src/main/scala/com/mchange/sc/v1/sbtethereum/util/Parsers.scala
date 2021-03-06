package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum._

import sbt.State

import sbt.complete.{FixedSetExamples,Parser}
import sbt.complete.DefaultParsers._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{jsonrpc,specification,EthAddress,EthHash}
import specification.Denominations
import jsonrpc.Abi

import com.mchange.sc.v2.failable._

import com.mchange.sc.v1.log.MLevel._

import scala.collection._

import play.api.libs.json._

object Parsers {
  private implicit lazy val logger = mlogger( this )

  private val ZWSP = "\u200B" // we add zero-width space to parser examples lists where we don't want autocomplete to apply to unique examples

  private val RawAddressParser = ( literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ) ).map( chars => EthAddress.apply( chars.mkString ) )

  private val EmptyAliasMap = immutable.SortedMap.empty[String,EthAddress]

  private def createSimpleAddressParser( tabHelp : String ) = Space.* ~> token( RawAddressParser, tabHelp )

  private def rawAliasParser( aliases : SortedMap[String,EthAddress] ) : Parser[String] = {
    aliases.keys.foldLeft( failure("not a known alias") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )
  }

  private def rawAliasedAddressParser( aliases : SortedMap[String,EthAddress] ) : Parser[EthAddress] = rawAliasParser( aliases ).map( aliases )

  private def createAddressParser( tabHelp : String, aliases : immutable.SortedMap[String,EthAddress] ) : Parser[EthAddress] = {
    if ( aliases.isEmpty ) {
      createSimpleAddressParser( tabHelp )
    } else {
      // println("CREATING COMPOUND PARSER")
      Space.* ~> token( RawAddressParser.examples( tabHelp ) | rawAliasedAddressParser( aliases ).examples( aliases.keySet, false ) )
    }
  }

  private [sbtethereum] val NewAliasParser = token(Space.* ~> ID, "<alias>") ~ createSimpleAddressParser("<hex-address>")

  private [sbtethereum] val RawIntegerParser = (Digit.+).map( chars => BigInt( chars.mkString ) )

  private [sbtethereum] def integerParser( tabHelp : String ) = token(Space.* ~> RawIntegerParser, tabHelp)

  private [sbtethereum] val RawAmountParser = ((Digit|literal('.')).+).map( chars => BigDecimal( chars.mkString ) )

  //private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.* ~> (Digit|literal('.')).+, tabHelp).map( chars => BigDecimal( chars.mkString ) )
  private [sbtethereum] def amountParser( tabHelp : String ) = token(Space.* ~> RawAmountParser, tabHelp)

  private [sbtethereum] val UnitParser = {
    val ( w, gw, s, f, e ) = ( "wei", "gwei", "szabo", "finney", "ether" );
    Space.* ~> token(literal(w) | literal(gw) | literal(s) | literal(f) | literal(e))
  }

  private [sbtethereum] def toValueInWei( amount : BigDecimal, unit : String ) = rounded(amount * BigDecimal(Denominations.Multiplier.BigInt( unit ))).toBigInt

  private [sbtethereum] def valueInWeiParser( tabHelp : String ) = {
    (amountParser( tabHelp ) ~ UnitParser).map { case ( amount, unit ) => toValueInWei( amount, unit ) }
  }

  private [sbtethereum] val SolcJVersionParser : Parser[Option[String]] = {
    val mandatory = compile.SolcJInstaller.SupportedVersions.foldLeft( failure("No supported versions") : Parser[String] )( ( nascent, next ) => nascent | literal(next) )
    Space.* ~> token(mandatory.?)
  }

  private [sbtethereum] def functionParser( abi : Abi, restrictToConstants : Boolean ) : Parser[Abi.Function] = {
    val namesToFunctions           = abi.functions.groupBy( _.name )

    val overloadedNamesToFunctions = namesToFunctions.filter( _._2.length > 1 )
    val nonoverloadedNamesToFunctions : Map[String,Abi.Function] = (namesToFunctions -- overloadedNamesToFunctions.keySet).map( tup => ( tup._1, tup._2.head ) )

    def createQualifiedNameForOverload( function : Abi.Function ) : String = function.name + "(" + function.inputs.map( _.`type` ).mkString(",") + ")"

    def createOverloadBinding( function : Abi.Function ) : ( String, Abi.Function ) = ( createQualifiedNameForOverload( function ), function )

    val qualifiedOverloadedNamesToFunctions : Map[String, Abi.Function] = overloadedNamesToFunctions.values.flatMap( _.map( createOverloadBinding ) ).toMap

    val processedNamesToFunctions = {
      val raw = (qualifiedOverloadedNamesToFunctions ++ nonoverloadedNamesToFunctions).toMap
      if ( restrictToConstants ) {
        raw.filter( _._2.constant )
      } else {
        raw
      }
    }

    val baseParser = processedNamesToFunctions.keySet.foldLeft( failure("not a function name") : Parser[String] )( ( nascent, next ) => nascent | literal( next ) )

    baseParser.map( processedNamesToFunctions )
  }

  private def inputParser( input : Abi.Parameter, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[String] = {
    val displayName = if ( input.name.length == 0 ) "mapping key" else input.name
    val sample = s"<${displayName}, of type ${input.`type`}>"
    if ( input.`type` == "address" && !mbAliases.isEmpty ) { // special case
      createAddressParser( sample, mbAliases.get ).map( _.hex )
    } else {
      token( (StringEscapable.map( str => s""""${str}"""") | NotQuoted).examples( FixedSetExamples( immutable.Set( sample, ZWSP ) ) ) )
    }
  }

  private def inputsParser( inputs : immutable.Seq[Abi.Parameter], mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[immutable.Seq[String]] = {
    val parserMaker : Abi.Parameter => Parser[String] = param => inputParser( param, mbAliases )
    inputs.map( parserMaker ).foldLeft( success( immutable.Seq.empty[String] ) )( (nascent, next) => nascent.flatMap( partial => Space.* ~> next.map( str => partial :+ str ) ) )
  }

  private def functionAndInputsParser( abi : Abi, restrictToConstants : Boolean, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[(Abi.Function, immutable.Seq[String])] = {
    token( functionParser( abi, restrictToConstants ) ).flatMap( function => inputsParser( function.inputs, mbAliases ).map( seq => ( function, seq ) ) )
  }

  private [sbtethereum] val DbQueryParser : Parser[String] = (any.*).map( _.mkString.trim )

  // delayed parsers
  private def constructorFromAbi( abi : Abi ) : Abi.Constructor = {
    abi.constructors.length match {
      case 0 => Abi.Constructor.noArg
      case 1 => abi.constructors.head
      case _ => throw new Exception( s"""Constructor overloading not supprted (or legal in solidity). Found multiple constructors: ${abi.constructors.mkString(", ")}""" )
    }
  }
  private def resultFromCompilation( contractName : String, compilation : jsonrpc.Compilation.Contract ) : Parser[ ( String, Option[ ( immutable.Seq[String], Abi, jsonrpc.Compilation.Contract ) ] ) ] = {
    val mbAbi = compilation.info.mbAbi
    mbAbi match {
      case Some( abiString ) => {
        val abi = Json.parse( abiString ).as[Abi]
        val ctor = constructorFromAbi( abi )
        inputsParser( ctor.inputs, None ).map( seq => ( contractName, Some( ( seq, abi, compilation ) ) ) )
      }
      case None => failure( s"ABI not available for compilation of contract '$contractName'" )
    }
  }
  private [sbtethereum] def genContractNamesConstructorInputsParser(
    state : State,
    mbContracts : Option[immutable.Map[String,jsonrpc.Compilation.Contract]]
  ) : Parser[(String, Option[(immutable.Seq[String], Abi, jsonrpc.Compilation.Contract)])] = {
    val contracts = mbContracts.getOrElse( immutable.Map.empty )
    val contractNames = immutable.TreeSet( contracts.keys.toSeq : _* )( Ordering.comparatorToOrdering( String.CASE_INSENSITIVE_ORDER ) )
    val exSet = if ( contractNames.isEmpty ) immutable.Set("<contract-name>", ZWSP) else contractNames // non-breaking space to prevent autocompletion to dummy example
    Space.* ~> token( NotSpace examples exSet ).flatMap { name =>
      contracts.get( name ) match {
        case None                => success( Tuple2( name, None ) )
        case Some( compilation ) => resultFromCompilation( name, compilation )
      }
    }
  }

  private [sbtethereum] def genAliasParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) = {
    // XXX: we accept ID (sbt's built-in identifier parser) when we don't have aliases,
    //      bc maybe there was just a problem getting the aliases but they exist. (kind of weak?)
    Space.* ~> mbIdAndMbAliases.map( _._2 ).flatten.fold( ID )( aliases => token( rawAliasParser( aliases ).examples( aliases.keySet, false ) ) )
  }

  private def _genGenericAddressParser( state : State, mbAliases : Option[immutable.SortedMap[String,EthAddress]] ) : Parser[EthAddress] = {
    val sample = mbAliases.fold( "<address-hex>" )( map => if ( map.isEmpty ) "<address-hex>" else "<address-hex or alias>" )
    createAddressParser( sample, mbAliases.getOrElse( EmptyAliasMap ) )
  }

  private [sbtethereum] def genGenericAddressParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[EthAddress] = {
    val mbAliases = mbIdAndMbAliases match {
      case Some( idAndMbAliases ) => idAndMbAliases._2
      case None => {
        WARNING.log("Failed to load aliases for generic address parser.")
        None
      }
    }
    _genGenericAddressParser( state, mbAliases )
  }

  private [sbtethereum] def genOptionalGenericAddressParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[Option[EthAddress]] = {
    genGenericAddressParser( state, mbIdAndMbAliases ).?
  }

  private [sbtethereum] def genRecipientAddressParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) = {
    mbIdAndMbAliases match {
      case Some( Tuple2(blockchainId, mbAliases) ) => {
        mbAliases match {
          case Some( aliases ) => createAddressParser("<recipient-address-hex or alias>", aliases)
          case None            => createAddressParser("<recipient-address-hex or alias>", EmptyAliasMap)
        }
      }
      case None => {
        WARNING.log("Failed to load blockchain ID and aliases for address, function, inputs, abi parser")
        failure( "Blockchain ID and alias list are unavailable, can't parse address and ABI" )
      }
    }
  }

  private [sbtethereum] def genEthSendEtherParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[( EthAddress, BigInt )] = {
    genRecipientAddressParser( state, mbIdAndMbAliases ) ~ valueInWeiParser("<amount>")
  }


          

  private [sbtethereum] def genContractAddressOrCodeHashParser(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[Either[EthAddress,EthHash]] = {
    val chp = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 64, 64 ), "<contract-code-hash>").map( chars => EthHash.withBytes( chars.mkString.decodeHex ) )
    genGenericAddressParser( state, mbIdAndMbAliases ).map( addr => Left[EthAddress,EthHash]( addr ) ) | chp.map( ch => Right[EthAddress,EthHash]( ch ) )
  }

  private [sbtethereum] def genAddressFunctionInputsAbiParser( restrictedToConstants : Boolean )(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[(EthAddress, Abi.Function, immutable.Seq[String], Abi)] = {
    mbIdAndMbAliases match {
      case Some( Tuple2(blockchainId, mbAliases) ) => { 
        _genGenericAddressParser( state, mbAliases ).map( a => ( a, abiForAddressOrEmpty(blockchainId,a) ) ).flatMap { case ( address, abi ) =>
          ( Space.* ~> functionAndInputsParser( abi, restrictedToConstants, mbAliases ) ).map { case ( function, inputs ) => ( address, function, inputs, abi ) }
        }
      }
      case None => {
        WARNING.log("Failed to load blockchain ID and aliases for address, function, inputs, abi parser")
        failure( "Blockchain ID and alias list are unavailable, can't parse address and ABI" )
      }
    }
  }
  private [sbtethereum] def genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants : Boolean  )(
    state : State,
    mbIdAndMbAliases : Option[(String,Option[immutable.SortedMap[String,EthAddress]])]
  ) : Parser[((EthAddress, Abi.Function, immutable.Seq[String], Abi), Option[BigInt])] = {
    genAddressFunctionInputsAbiParser( restrictedToConstants )( state, mbIdAndMbAliases ).flatMap { afia =>
      if ( afia._2.payable ) {
        valueInWeiParser("[ETH to pay, optional]").?.flatMap( mbv => success(  ( afia, mbv ) ) ) // useless flatmap rather than map
      } else {
        success( ( afia, None ) )
      }
    }
  }
  private [sbtethereum] def genLiteralSetParser(
    state : State,
    mbLiterals : Option[immutable.Set[String]]
  ) : Parser[String] = {
    Space.* ~> token( mbLiterals.fold( failure("Failed to load acceptable values") : Parser[String] )( _.foldLeft( failure("No acceptable values") : Parser[String] )( ( nascent, next ) => nascent | literal(next) ) ) )
  }
}
